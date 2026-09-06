#!/usr/bin/python

"""Locust workload that injects a timed Kubernetes Deployment outage."""

import csv
import logging
import os
import time
from pathlib import Path

import gevent
import requests
from locust import FastHttpUser, constant, events, task

LOG = logging.getLogger(__name__)
TOKEN_PATH = Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
CA_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
EVENTS_PATH = Path(os.getenv("FAULT_EVENTS_PATH", "/tmp/fault_events.csv"))

fault_greenlet = None


def setting(name, default):
    return os.getenv(name, str(default))


def record_event(event, replicas, detail=""):
    new_file = not EVENTS_PATH.exists()
    EVENTS_PATH.parent.mkdir(parents=True, exist_ok=True)
    with EVENTS_PATH.open("a", newline="") as file:
        writer = csv.writer(file)
        if new_file:
            writer.writerow(["timestamp", "event", "deployment", "replicas", "detail"])
        writer.writerow(
            [time.time(), event, setting("FAULT_DEPLOYMENT", "payment"), replicas, detail]
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
    record_event("scaled", replicas)
    LOG.warning("Scaled deployment/%s to %d replica(s)", deployment, replicas)


def inject_fault(environment):
    try:
        gevent.sleep(float(setting("FAULT_AFTER_SECONDS", 120)))
        scale_deployment(0)
        gevent.sleep(float(setting("FAULT_DURATION_SECONDS", 60)))
        scale_deployment(int(setting("FAULT_RESTORE_REPLICAS", 1)))
    except gevent.GreenletExit:
        raise
    except Exception as error:
        LOG.exception("Fault injection failed")
        record_event("error", "", str(error))
        environment.process_exit_code = 2
        environment.runner.quit()


def start_fault_controller(environment):
    global fault_greenlet
    if fault_greenlet is not None:
        return
    if not TOKEN_PATH.exists():
        raise RuntimeError(
            "Kubernetes service-account token is missing; run this workload inside Kubernetes"
        )
    EVENTS_PATH.unlink(missing_ok=True)
    record_event("test-start", int(setting("FAULT_RESTORE_REPLICAS", 1)))
    fault_greenlet = gevent.spawn(inject_fault, environment)


@events.test_stop.add_listener
def stop_fault_controller(environment, **kwargs):
    global fault_greenlet
    if fault_greenlet is None:
        return
    if fault_greenlet is not None:
        fault_greenlet.kill(block=True)
        fault_greenlet = None
    # Restoring unconditionally closes the small interruption window between the
    # API accepting a scale-to-zero request and the controller recording it.
    try:
        scale_deployment(int(setting("FAULT_RESTORE_REPLICAS", 1)))
    except Exception as error:
        LOG.exception("Could not restore the faulted Deployment")
        record_event("restore-error", "", str(error))
        environment.process_exit_code = 2
    record_event("test-stop", int(setting("FAULT_RESTORE_REPLICAS", 1)))


class FaultToleranceWarehouseUser(FastHttpUser):
    # Keep the offered load stable across baseline, outage, and recovery.
    wait_time = constant(float(setting("REQUEST_PERIOD_SECONDS", 3)))

    def on_start(self):
        # Tying activation to this class guarantees that choosing the ordinary
        # workload in Locust's class picker can never inject a fault.
        start_fault_controller(self.environment)

    @task
    def order_fulfillment(self):
        self.client.get("/orderFulfillment", name="orderFulfillment")
