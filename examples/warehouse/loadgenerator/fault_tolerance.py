#!/usr/bin/python

"""Locust workload that injects a timed Kubernetes Deployment outage."""

import csv
import logging
import os
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import gevent
import requests
from locust import FastHttpUser, between, events, task

LOG = logging.getLogger(__name__)
TOKEN_PATH = Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
EVENTS_PATH = Path(os.getenv("FAULT_EVENTS_PATH", "/tmp/fault_events.csv"))

fault_greenlet = None
run_id = None
test_started_monotonic = None


@events.test_start.add_listener
def initialise_run_identity(environment, **kwargs):
    global run_id, test_started_monotonic
    run_id = os.getenv("BENCHMARK_RUN_ID", str(uuid.uuid4()))
    test_started_monotonic = time.monotonic()


def setting(name, default):
    return os.getenv(name, str(default))


@events.init_command_line_parser.add_listener
def add_fault_timing_arguments(parser):
    parser.add_argument(
        "--fault-after-seconds",
        type=float,
        default=float(setting("FAULT_AFTER_SECONDS", 120)),
        help="Seconds from test start before the deployment is scaled down",
        include_in_web_ui=True,
    )
    parser.add_argument(
        "--fault-duration-seconds",
        type=float,
        default=float(setting("FAULT_DURATION_SECONDS", 60)),
        help="Seconds to keep the deployment scaled down before restoring it",
        include_in_web_ui=True,
    )


def record_event(phase, replicas="", detail=""):
    new_file = not EVENTS_PATH.exists()
    EVENTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    with EVENTS_PATH.open("a", newline="") as file:
        writer = csv.writer(file)
        if new_file:
            writer.writerow(["run_id", "elapsed_seconds", "timestamp_utc", "phase", "deployment", "replicas", "detail"])
        writer.writerow(
            [run_id, time.monotonic() - test_started_monotonic, datetime.now(timezone.utc).isoformat(), phase,
             setting("FAULT_DEPLOYMENT", "payment"), replicas, detail]
        )


def scale_deployment(replicas):
    host = os.environ["KUBERNETES_SERVICE_HOST"]
    port = os.environ.get("KUBERNETES_SERVICE_PORT_HTTPS", "443")
    namespace = setting("POD_NAMESPACE", "default")
    deployment = setting("FAULT_DEPLOYMENT", "payment")
    url = (
        f"https://{host}:{port}/apis/apps/v1/namespaces/{namespace}/"
        f"deployments/{deployment}/scale"
    )
    response = requests.patch(
        url,
        headers={
            "Authorization": f"Bearer {TOKEN_PATH.read_text().strip()}",
            "Content-Type": "application/merge-patch+json",
        },
        json={"spec": {"replicas": replicas}},
        verify=CA_PATH,
        timeout=15,
    )
    response.raise_for_status()
    record_event("scale-api-acknowledged", replicas)
    LOG.warning("Scaled deployment/%s to %d replica(s)", deployment, replicas)


def inject_fault(environment):
    try:
        gevent.sleep(environment.parsed_options.fault_after_seconds)
        record_event("scale-down-api-request", 0)
        scale_deployment(0)
        wait_for_ready_replicas(0, "target-unavailable")
        gevent.sleep(environment.parsed_options.fault_duration_seconds)
        replicas = int(setting("FAULT_RESTORE_REPLICAS", 1))
        record_event("scale-up-api-request", replicas)
        scale_deployment(replicas)
        wait_for_ready_replicas(replicas, "target-ready")
    except gevent.GreenletExit:
        raise
    except Exception as error:
        LOG.exception("Fault injection failed")
        record_event("error", "", str(error))
        environment.process_exit_code = 2
        environment.runner.quit()


def start_fault_controller(environment):
    global fault_greenlet, run_id, test_started_monotonic
    if fault_greenlet is not None:
        return
    if not TOKEN_PATH.exists():
        raise RuntimeError(
            "Kubernetes service-account token is missing; run this workload inside Kubernetes"
        )
    EVENTS_PATH.unlink(missing_ok=True)
    record_event("test-start", int(setting("FAULT_RESTORE_REPLICAS", 1)))
    record_event("warm-up-end", int(setting("FAULT_RESTORE_REPLICAS", 1)), "configured at test start")
    fault_greenlet = gevent.spawn(inject_fault, environment)


@events.test_stop.add_listener
def stop_fault_controller(environment, **kwargs):
    global fault_greenlet
    if fault_greenlet is None:
        return
    controller = fault_greenlet
    fault_greenlet = None
    # runner.quit() may synchronously fire test_stop from inside the fault
    # controller's own exception handler. Killing the current greenlet here
    # would abort this listener before it can restore the Deployment.
    if controller is not gevent.getcurrent():
        controller.kill(block=True)
    # Restoring unconditionally closes the small interruption window between the
    # API accepting a scale-to-zero request and the controller recording it.
    try:
        replicas = int(setting("FAULT_RESTORE_REPLICAS", 1))
        record_event("scale-up-api-request", replicas)
        scale_deployment(replicas)
    except Exception as error:
        LOG.exception("Could not restore the faulted Deployment")
        record_event("restore-error", "", str(error))
        environment.process_exit_code = 2
    record_event("test-stop", int(setting("FAULT_RESTORE_REPLICAS", 1)))


class FaultToleranceWarehouseUser(FastHttpUser):
    # Keep the offered load stable across baseline, outage, and recovery.
    wait_time = between(1, 5)

    def on_start(self):
        # Tying activation to this class guarantees that choosing the ordinary
        # workload in Locust's class picker can never inject a fault.
        start_fault_controller(self.environment)

    @task
    def order_fulfillment(self):
        self.client.get("/orderFulfillment", name="orderFulfillment", headers={"X-Benchmark-Run-Id": run_id})


def wait_for_ready_replicas(expected, phase):
    """Use the deployment status as the durable Kubernetes availability marker."""
    host = os.environ["KUBERNETES_SERVICE_HOST"]
    port = os.environ.get("KUBERNETES_SERVICE_PORT_HTTPS", "443")
    namespace, deployment = setting("POD_NAMESPACE", "default"), setting("FAULT_DEPLOYMENT", "payment")
    url = f"https://{host}:{port}/apis/apps/v1/namespaces/{namespace}/deployments/{deployment}"
    deadline = time.monotonic() + float(setting("FAULT_READY_TIMEOUT_SECONDS", 120))
    while time.monotonic() < deadline:
        response = requests.get(url, headers={"Authorization": f"Bearer {TOKEN_PATH.read_text().strip()}"},
                                verify=CA_PATH, timeout=15)
        response.raise_for_status()
        ready = response.json().get("status", {}).get("readyReplicas", 0)
        if ready == expected:
            record_event(phase, expected)
            return
        gevent.sleep(1)
    record_event("observation-timeout", expected, phase)
