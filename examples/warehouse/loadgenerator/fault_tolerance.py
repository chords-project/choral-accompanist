"""Single-process, fixed-arrival recovery benchmark. Start each run in the web UI."""
import json
import logging
import os
import time
import uuid
from pathlib import Path

import gevent
from gevent.event import Event
from gevent.pool import Group
from locust import User, events, task
import requests
from locust.runners import MasterRunner, WorkerRunner
from benchmark_core import SYSTEMS, arrival, resolve

LOG = logging.getLogger(__name__)
ROOT = Path(os.getenv("BENCHMARK_ARTIFACTS", "/runs"))
TOKEN = Path("/var/run/secrets/kubernetes.io/serviceaccount/token")
CA = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"
current = None


@events.init_command_line_parser.add_listener
def arguments(parser):
    active = os.getenv("BENCHMARK_SYSTEM")
    parser.add_argument("--benchmark-system", choices=[active] if active else list(SYSTEMS), default=active or "accompanist",
                        include_in_web_ui=True, help="accompanist → payment; temporal → temporal-worker-payment")
    parser.add_argument("--benchmark-endpoint", default="", include_in_web_ui=True,
                        help="Optional HTTP endpoint override; fault target remains selected above")
    for flag, default, kind in [("request-rate", 5, float), ("submission-duration", 300, float),
                                ("fault-after-seconds", 120, float), ("fault-duration-seconds", 60, float),
                                ("max-concurrent-requests", 0, int), ("drain-timeout", 300, float)]:
        parser.add_argument("--" + flag, type=kind, default=default, include_in_web_ui=True,
                            help=flag + (" (0 = rate × (submission duration + drain))" if default == 0 else ""))


class Evidence:
    def __init__(self, path):
        self.path = path
        self.path.mkdir(parents=True)

    def save(self, name, value):
        temp = self.path / (name + ".tmp")
        with temp.open("w") as stream:
            json.dump(value, stream, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        temp.replace(self.path / name)

    def append(self, name, value):
        # No yield between write and fsync; a single Locust process owns this log.
        with (self.path / name).open("a") as stream:
            stream.write(json.dumps(value) + "\n")
            stream.flush()
            os.fsync(stream.fileno())


class FaultController:
    def __init__(self, run):
        self.run = run
        self.original = None
        self.changed = False

    def api(self, method="GET", suffix="", **kwargs):
        namespace = os.getenv("POD_NAMESPACE", "default")
        host = os.environ["KUBERNETES_SERVICE_HOST"]
        port = os.getenv("KUBERNETES_SERVICE_PORT_HTTPS", "443")
        url = f"https://{host}:{port}/apis/apps/v1/namespaces/{namespace}/deployments/{self.run.config['deployment']}{suffix}"
        response = requests.request(method, url, headers={"Authorization": "Bearer " + TOKEN.read_text().strip(),
                                    "Content-Type": "application/merge-patch+json"}, verify=CA, timeout=15, **kwargs)
        response.raise_for_status()
        return response.json()

    def observe(self, replicas, phase):
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            data = self.api()
            status = data.get("status", {})
            observed = status.get("observedGeneration", 0) >= data["metadata"]["generation"]
            count = status.get("replicas", 0)
            ready = status.get("readyReplicas", 0)
            available = status.get("availableReplicas", 0)
            if observed and count == replicas and ready == replicas and available == replicas:
                self.run.event(phase, replicas=replicas)
                return
            gevent.sleep(0.5)
        raise TimeoutError(f"{phase}: did not observe {replicas} replicas within 120 seconds")

    def prepare(self):
        data = self.api()
        self.original = data["spec"].get("replicas", 1)
        if self.original < 1:
            raise RuntimeError("Payment deployment must initially have at least one replica")
        self.run.config["original_replicas"] = self.original
        self.run.evidence.save("fault-deployment.json", data)
        self.observe(self.original, "initial-ready")

    def scale(self, replicas):
        self.run.event("scale-request", replicas=replicas)
        self.api("PATCH", "/scale", json={"spec": {"replicas": replicas}})
        self.run.event("scale-acknowledged", replicas=replicas)

    def inject(self):
        try:
            if self.run.stop.wait(timeout=max(0, self.run.started + self.run.config["fault_after"] - time.monotonic())):
                return
            self.changed = True  # Before API call: its outcome may be ambiguous.
            self.scale(0)
            self.observe(0, "target-unavailable")
            self.run.stop.wait(timeout=self.run.config["fault_duration"])
        except Exception as error:
            self.run.error(f"fault controller: {error}")
            self.run.stop.set()
        finally:
            self.restore()

    def restore(self):
        if not self.changed:
            return
        # Retry restoration on transient API failure, preserving the original count.
        for attempt in range(3):
            try:
                self.scale(self.original)
                self.observe(self.original, "target-ready")
                self.changed = False
                return
            except Exception as error:
                self.run.error(f"restoration attempt {attempt + 1}: {error}")
                gevent.sleep(1)
        self.run.error("restoration failed")


class Run:
    def __init__(self, environment):
        self.environment = environment
        self.config = resolve(environment.parsed_options)
        active_system = os.getenv("BENCHMARK_SYSTEM")
        if active_system and active_system != self.config["system"]:
            raise ValueError(f"Only the {active_system} stack is deployed")
        ROOT.mkdir(parents=True, exist_ok=True)
        # Collection releases this persistent guard only after reconciling durable work.
        self.run_id = str(uuid.uuid4())
        with (ROOT / "active-run").open("x") as guard:
            guard.write(self.run_id)
            guard.flush()
            os.fsync(guard.fileno())
        self.evidence = Evidence(ROOT / self.run_id)
        self.config.update(schema_version=1, run_id=self.run_id, created_at=time.time(),
                           namespace=os.getenv("POD_NAMESPACE", "default"))
        self.started = time.monotonic()
        self.stop = Event()
        self.done = Event()
        self.requests = Group()
        self.errors = []
        self.fault = FaultController(self)
        self.worker = None
        self.evidence.save("run-config.json", self.config)
        for name in ("requests.jsonl", "fault-events.jsonl", "validation-errors.jsonl"):
            (self.evidence.path / name).touch()

    def event(self, phase, **values):
        self.evidence.append("fault-events.jsonl", dict(phase=phase, timestamp=time.time(), **values))

    def error(self, message):
        self.errors.append(message)
        self.evidence.append("validation-errors.jsonl", {"error": message})
        self.environment.process_exit_code = 2
        LOG.error(message)

    def submit(self, record):
        began = time.monotonic()
        record = dict(record, timestamp=time.time())
        self.evidence.append("requests.jsonl", dict(record, event="dispatch"))
        response = None
        error = None
        try:
            # requests has no automatic retries. Each greenlet owns its connection.
            response = requests.get(self.config["endpoint"] + "/orderFulfillment", headers={
                "X-Benchmark-Run-Id": self.run_id, "X-Benchmark-Request-Id": record["request_id"]},
                timeout=(10, self.config["duration"] + self.config["drain_timeout"]), allow_redirects=False)
            response.raise_for_status()
        except gevent.GreenletExit:
            error = RuntimeError("HTTP request interrupted at drain deadline")
            raise
        except Exception as exc:
            error = exc
        finally:
            latency = (time.monotonic() - began) * 1000
            self.evidence.append("requests.jsonl", dict(record, event="response", timestamp=time.time(),
                http_latency_ms=latency, status=response.status_code if response is not None else None,
                error=str(error) if error else None))
            self.environment.events.request.fire(request_type="GET", name="orderFulfillment", response_time=latency,
                response_length=len(response.content) if response is not None else 0, exception=error,
                context={}, response=response, start_time=record["timestamp"], url=self.config["endpoint"])

    def execute(self):
        fault_task = None
        try:
            self.fault.prepare()
            self.started = time.monotonic()
            wall = time.time()
            self.config.update(started_at=wall, drain_deadline=wall + self.config["duration"] + self.config["drain_timeout"])
            self.evidence.save("run-config.json", self.config)
            self.event("test-start")
            fault_task = gevent.spawn(self.fault.inject)
            index = 0
            while index / self.config["rate"] < self.config["duration"] and not self.stop.is_set():
                due = index / self.config["rate"]
                if self.stop.wait(timeout=max(0, self.started + due - time.monotonic())):
                    break
                request_id = str(uuid.uuid4())
                record = dict(request_id=request_id, scheduled_at=wall + due, timestamp=time.time(),
                              execution_id=f"benchmark-{self.run_id}-{request_id}" if self.config["system"] == "temporal" else None)
                decision = arrival(index, time.monotonic() - self.started, self.config["rate"],
                                   len(self.requests), self.config["max_concurrent"])
                self.evidence.append("requests.jsonl", dict(record, event="scheduled"))
                if decision == "dispatch":
                    self.requests.spawn(self.submit, record)
                else:
                    self.evidence.append("requests.jsonl", dict(record, event="missed", reason=decision))
                    self.error(decision)
                index += 1
                gevent.sleep(0)
            if not self.stop.is_set():
                self.stop.wait(timeout=max(0, self.started + self.config["duration"] - time.monotonic()))
            elif index / self.config["rate"] < self.config["duration"]:
                self.error("Submissions stopped early")
        except Exception as error:
            self.error(f"run: {error}")
        finally:
            self.stop.set()
            self.event("submissions-stopped")
            if fault_task is not None:
                fault_task.join()  # Stop event interrupts the outage sleep; finally restores.
            self.fault.restore()
            remaining = max(0, self.config.get("drain_deadline", time.time()) - time.time())
            self.requests.join(timeout=remaining)
            if self.requests:
                self.error("HTTP drain timeout; durable outcomes require collection")
                self.requests.kill(block=True)
            self.event("test-stop")
            self.evidence.save("run-status.json", {"stopped": True, "valid": not self.errors,
                                                   "errors": self.errors, "restored": not self.fault.changed})
            self.done.set()
            if self.environment.runner.state == "running":
                gevent.spawn(self.environment.runner.stop)


@events.init.add_listener
def initialise(environment, **kwargs):
    if isinstance(environment.runner, (MasterRunner, WorkerRunner)):
        raise SystemExit("Recovery benchmark supports a single Locust process; distributed execution is forbidden")
    if environment.web_ui:
        from flask import request
        from html import escape
        selected = os.getenv("BENCHMARK_SYSTEM", environment.parsed_options.benchmark_system)
        target = SYSTEMS[selected]
        @environment.web_ui.app.after_request
        def benchmark_target(response):
            if request.path == "/" and response.mimetype == "text/html":
                notice = (f"<aside style='padding:12px;background:#fff3cd;color:#292929'>"
                          f"Benchmark: {escape(selected)} · Fault target: deployment/{escape(target['deployment'])} · "
                          f"Collector: {escape(target['collector'])} · Default endpoint: {escape(target['endpoint'])}. "
                          "An HTTP endpoint override keeps this fault target.</aside>")
                response.set_data(response.get_data(as_text=True).replace("<body>", "<body>" + notice))
            return response


@events.test_start.add_listener
def start(environment, **kwargs):
    global current
    if isinstance(environment.runner, (MasterRunner, WorkerRunner)):
        raise RuntimeError("Distributed benchmark execution is forbidden")
    try:
        current = Run(environment)
        LOG.warning("Run %s: endpoint=%s fault deployment=%s collector=%s", current.run_id,
                    current.config["endpoint"], current.config["deployment"], current.config["collector"])
        current.worker = gevent.spawn(current.execute)
    except Exception:
        LOG.exception("Benchmark start rejected (collect the preceding run before restarting)")
        environment.process_exit_code = 2
        gevent.spawn(environment.runner.stop)


@events.test_stopping.add_listener
def stopping(environment, **kwargs):
    if current is not None and not current.done.is_set():
        current.stop.set()
        current.done.wait()


class RecoveryBenchmarkUser(User):
    """UI user count has no effect: the test_start listener owns the only scheduler."""
    @task
    def idle(self):
        gevent.sleep(1)


@events.quitting.add_listener
def quitting(environment, **kwargs):
    stopping(environment)
