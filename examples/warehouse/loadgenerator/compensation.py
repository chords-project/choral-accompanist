"""Fixed-count stock-out benchmark, run once per Locust UI start."""
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
from locust.runners import MasterRunner, WorkerRunner
import requests

from benchmark_core import SYSTEMS, arrival, resolve_compensation
from stock_db import set_stock

LOG = logging.getLogger(__name__)
ROOT = Path(os.getenv("BENCHMARK_ARTIFACTS", "/runs"))
current = None


@events.init_command_line_parser.add_listener
def arguments(parser):
    active = os.getenv("BENCHMARK_SYSTEM")
    parser.add_argument("--benchmark-system", choices=[active] if active else list(SYSTEMS),
                        default=active or "accompanist", include_in_web_ui=True)
    parser.add_argument("--benchmark-endpoint", default="", include_in_web_ui=True)
    parser.add_argument("--stock-count", type=int, default=750, include_in_web_ui=True)
    parser.add_argument("--request-rate", type=float, default=5, include_in_web_ui=True)
    parser.add_argument("--max-concurrent-requests", type=int, default=0, include_in_web_ui=True,
                        help="0 derives a cap from the submission and drain windows")
    parser.add_argument("--drain-timeout", type=float, default=300, include_in_web_ui=True)


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
        with (self.path / name).open("a") as stream:
            stream.write(json.dumps(value) + "\n")
            stream.flush()
            os.fsync(stream.fileno())


class Run:
    def __init__(self, environment):
        self.environment = environment
        self.config = resolve_compensation(environment.parsed_options)
        active = os.getenv("BENCHMARK_SYSTEM")
        if active and active != self.config["system"]:
            raise ValueError(f"Only the {active} stack is deployed")
        ROOT.mkdir(parents=True, exist_ok=True)
        self.run_id = str(uuid.uuid4())
        with (ROOT / "active-run").open("x") as guard:
            guard.write(self.run_id)
            guard.flush()
            os.fsync(guard.fileno())
        self.evidence = Evidence(ROOT / self.run_id)
        self.config.update(schema_version=2, run_id=self.run_id, created_at=time.time(),
                           namespace=os.getenv("POD_NAMESPACE", "default"))
        self.stop = Event()
        self.done = Event()
        self.requests = Group()
        self.errors = []
        self.started = None
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
            self.environment.events.request.fire(request_type="GET", name="orderFulfillment",
                response_time=latency, response_length=len(response.content) if response is not None else 0,
                exception=error, context={}, response=response, start_time=record["timestamp"],
                url=self.config["endpoint"])

    def execute(self):
        try:
            initial = set_stock(self.config["stock_count"])
            self.config["initial_stock"] = initial
            self.event("stock-set", stock=initial)
            self.started = time.monotonic()
            wall = time.time()
            self.config.update(started_at=wall, drain_deadline=wall + self.config["duration"] + self.config["drain_timeout"])
            self.evidence.save("run-config.json", self.config)
            self.event("test-start")
            scheduled = 0
            for index in range(self.config["request_count"]):
                if self.stop.wait(timeout=max(0, self.started + index / self.config["rate"] - time.monotonic())):
                    break
                request_id = str(uuid.uuid4())
                record = dict(request_id=request_id, scheduled_at=wall + index / self.config["rate"],
                              timestamp=time.time(), execution_id=f"benchmark-{self.run_id}-{request_id}"
                              if self.config["system"] == "temporal" else None)
                decision = arrival(index, time.monotonic() - self.started, self.config["rate"],
                                   len(self.requests), self.config["max_concurrent"])
                self.evidence.append("requests.jsonl", dict(record, event="scheduled"))
                scheduled += 1
                if decision == "dispatch":
                    self.requests.spawn(self.submit, record)
                else:
                    self.evidence.append("requests.jsonl", dict(record, event="missed", reason=decision))
                    self.error(decision)
                gevent.sleep(0)
            if not self.stop.is_set():
                self.stop.wait(timeout=max(0, self.started + self.config["duration"] - time.monotonic()))
            elif scheduled < self.config["request_count"]:
                self.error("Submissions stopped early")
        except Exception as exc:
            self.error(f"run: {exc}")
        finally:
            self.stop.set()
            self.event("submissions-stopped")
            remaining = max(0, self.config.get("drain_deadline", time.time()) - time.time())
            self.requests.join(timeout=remaining)
            if self.requests:
                self.error("HTTP drain timeout; durable outcomes require collection")
                self.requests.kill(block=True)
            self.event("test-stop")
            self.evidence.save("run-status.json", {"stopped": True, "valid": not self.errors,
                                                   "errors": self.errors, "restored": True})
            self.done.set()
            if self.environment.runner.state == "running":
                gevent.spawn(self.environment.runner.stop)


@events.init.add_listener
def initialise(environment, **kwargs):
    if isinstance(environment.runner, (MasterRunner, WorkerRunner)):
        raise SystemExit("Stock-out benchmark supports a single Locust process")


@events.test_start.add_listener
def start(environment, **kwargs):
    global current
    try:
        current = Run(environment)
        LOG.warning("Stock-out run %s: %s, %s requests", current.run_id,
                    current.config["system"], current.config["request_count"])
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


class StockOutBenchmarkUser(User):
    @task
    def idle(self):
        gevent.sleep(1)


@events.quitting.add_listener
def quitting(environment, **kwargs):
    stopping(environment)
