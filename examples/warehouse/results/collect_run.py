#!/usr/bin/env python3
"""Collect durable outcomes into a versioned, fully offline bundle."""
import argparse
import asyncio
import csv
import io
import json
import subprocess
import time
import uuid
from pathlib import Path
from export_run_bundle import export, read_jsonl


def timestamp(value):
    from datetime import datetime
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() if value else None


def releasable(manifest, collection_errors):
    """Only collected terminal work may release the persistent run guard."""
    return manifest["complete"] and manifest["restored"] and not collection_errors


class Kubernetes:
    def __init__(self, context, namespace):
        self.command = ["kubectl", "--context", context, "--namespace", namespace]

    def run(self, *args):
        return subprocess.check_output([*self.command, *args], text=True, timeout=60)

    def snapshot(self, bundle):
        for name, args in {"cluster-version": ("version", "-o", "json"),
                           "deployments": ("get", "deployments", "-o", "json"),
                           "pods": ("get", "pods", "-o", "json"),
                           "nodes": ("get", "nodes", "-o", "json")}.items():
            try:
                (bundle / "raw" / (name + ".json")).write_text(self.run(*args))
            except Exception as exc:
                yield f"Cannot snapshot {name}: {exc}"


ACCOMPANIST_COMPENSATIONS = {
    "warehouse": "checkItemInStockAndReserveForOrder",
    "payment": "takeMoneyFromCustomer",
    "loyalty": "awardPointsToCustomer",
}
TEMPORAL_COMPENSATIONS = {
    "cancelDelivery", "compensatePointsFromCustomer", "refundCustomer", "cancelOrderReservation",
}


def collect_accompanist(kube, run_id, bundle, fulfillment=False):
    sql = f"""SELECT COALESCE(s.session_id, r.session_id) AS session_id, COALESCE(s.run_id, r.run_id) AS run_id, s.session_state, s.started_at, s.completed_at,
        s.failed_at, s.attempt_count, s.restart_count, r.request_id, r.accepted_at
        FROM session_states s FULL OUTER JOIN benchmark_requests r ON s.session_id = r.session_id
        WHERE s.run_id = '{run_id}'::uuid OR r.run_id = '{run_id}'::uuid;"""
    raw = kube.run("exec", "deploy/db-warehouse", "--", "psql", "-U", "postgres", "-d", "warehouse", "--csv", "-c", sql)
    (bundle / "raw" / "sessions.csv").write_text(raw)
    results = []
    for row in csv.DictReader(io.StringIO(raw)):
        state = row["session_state"]
        results.append(dict(request_id=row["request_id"], execution_id=row["session_id"],
                            status="completed" if state == "completed" else "failed" if state == "failed" else "running",
                            started_at=timestamp(row["started_at"]),
                            terminal_at=timestamp(row["completed_at"] if state == "completed" else row["failed_at"] if state == "failed" else None)))
    if not fulfillment:
        return results

    evidence = {}
    for service, transaction in ACCOMPANIST_COMPENSATIONS.items():
        database = service
        sql = f"""SELECT s.session_id, s.failed_at, t.transaction_name, t.transaction_state, t.compensated_at
            FROM session_states s LEFT JOIN transaction_states t ON t.session_id = s.session_id
            WHERE s.run_id = '{run_id}'::uuid;"""
        raw = kube.run("exec", f"deploy/db-{service}", "--", "psql", "-U", "postgres", "-d", database,
                       "--csv", "-c", sql)
        (bundle / "raw" / f"compensations-{service}.csv").write_text(raw)
        for row in csv.DictReader(io.StringIO(raw)):
            item = evidence.setdefault(row["session_id"], {"failures": [], "nodes": {}})
            if row["failed_at"]:
                item["failures"].append(timestamp(row["failed_at"]))
            if row["transaction_name"] == transaction:
                item["nodes"][service] = {
                    "transaction": transaction,
                    "state": row["transaction_state"],
                    "completed_at": timestamp(row["compensated_at"]),
                }
    for result in results:
        if result["status"] != "failed":
            continue
        item = evidence.get(str(result["execution_id"]), {"failures": [], "nodes": {}})
        complete = (set(item["nodes"]) == set(ACCOMPANIST_COMPENSATIONS) and
                    all(node["state"] == "compensated" and node["completed_at"] is not None
                        for node in item["nodes"].values()) and item["failures"])
        result["compensations"] = item["nodes"]
        result["compensation_pending"] = not complete
        if complete:
            started = min(item["failures"])
            completed = max(node["completed_at"] for node in item["nodes"].values())
            result.update(compensation_started_at=started, compensation_completed_at=completed,
                          compensation_duration=max(0, completed - started), terminal_at=completed)
    return results


def temporal_compensation_evidence(events):
    """Extract final-activity failure and actual compensation completions from protobuf history events."""
    scheduled = {}
    failure_at = None
    completed = {}
    for event in events:
        when = event.event_time.ToDatetime().replace(tzinfo=__import__('datetime').timezone.utc).timestamp()
        if event.HasField("activity_task_scheduled_event_attributes"):
            scheduled[event.event_id] = event.activity_task_scheduled_event_attributes.activity_type.name
        elif event.HasField("activity_task_failed_event_attributes"):
            name = scheduled.get(event.activity_task_failed_event_attributes.scheduled_event_id)
            normalized = name[:1].lower() + name[1:] if name else None
            if normalized == "packageAndSendOrder":
                failure_at = when
        elif event.HasField("activity_task_completed_event_attributes"):
            name = scheduled.get(event.activity_task_completed_event_attributes.scheduled_event_id)
            normalized = name[:1].lower() + name[1:] if name else None
            if normalized in TEMPORAL_COMPENSATIONS:
                completed[normalized] = when
    if failure_at is None or set(completed) != TEMPORAL_COMPENSATIONS:
        return None, completed
    finished = max(completed.values())
    return dict(compensation_started_at=failure_at, compensation_completed_at=finished,
                compensation_duration=max(0, finished - failure_at), terminal_at=finished), completed


async def collect_temporal(address, namespace, requests, bundle, records=None):
    from datetime import timedelta
    from temporalio.client import Client
    from temporalio.api.common.v1 import WorkflowExecution
    from temporalio.api.workflowservice.v1 import DescribeWorkflowExecutionRequest, GetWorkflowExecutionHistoryRequest
    from temporalio.service import RPCError, RPCStatusCode
    from google.protobuf.json_format import MessageToDict
    client = await Client.connect(address, namespace=namespace)
    if records is None:
        records = []
    for request in requests:
        execution_id = request["execution_id"]
        execution = WorkflowExecution(workflow_id=execution_id)
        try:
            description = await client.workflow_service.describe_workflow_execution(
                DescribeWorkflowExecutionRequest(namespace=namespace, execution=execution), timeout=timedelta(seconds=15))
        except RPCError as exc:
            if exc.status == RPCStatusCode.NOT_FOUND:
                continue  # Missing is reconciled as unknown, never as success.
            raise
        info = description.workflow_execution_info
        execution.run_id = info.execution.run_id
        evidence = {"description": MessageToDict(description), "history_pages": []}
        token = b""
        history_events = []
        while True:
            page = await client.workflow_service.get_workflow_execution_history(GetWorkflowExecutionHistoryRequest(
                namespace=namespace, execution=execution, next_page_token=token, maximum_page_size=1000), timeout=timedelta(seconds=15))
            evidence["history_pages"].append(MessageToDict(page))
            history_events.extend(page.history.events)
            token = page.next_page_token
            if not token:
                break
        (bundle / "raw" / (execution_id + ".json")).write_text(json.dumps(evidence, indent=2))
        record = dict(request_id=request["request_id"], execution_id=execution_id, temporal_run_id=execution.run_id,
                            status="completed" if info.status == 2 else "running" if info.status == 1 else "failed",
                            started_at=info.start_time.ToDatetime().replace(tzinfo=__import__('datetime').timezone.utc).timestamp(),
                            terminal_at=info.close_time.ToDatetime().replace(tzinfo=__import__('datetime').timezone.utc).timestamp()
                            if info.HasField("close_time") else None)
        if request.get("collect_compensations") and record["status"] == "failed":
            timing, completions = temporal_compensation_evidence(history_events)
            record["compensations"] = {name: {"completed_at": when} for name, when in completions.items()}
            record["compensation_pending"] = timing is None
            if timing:
                record.update(timing)
        records.append(record)
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_id", type=lambda value: str(uuid.UUID(value)))
    parser.add_argument("--context", default=None)
    parser.add_argument("--namespace", default="default")
    parser.add_argument("--output", type=Path, default=Path(__file__).parent)
    parser.add_argument("--temporal-address", default="127.0.0.1:17233", help="Local address; forwarded automatically unless --no-port-forward")
    parser.add_argument("--temporal-namespace", default="default")
    parser.add_argument("--no-port-forward", action="store_true")
    args = parser.parse_args()
    context = args.context or subprocess.check_output(["kubectl", "config", "current-context"], text=True).strip()
    kube = Kubernetes(context, args.namespace)
    bundle = args.output / args.run_id
    bundle.mkdir(parents=True, exist_ok=True)
    (bundle / "raw").mkdir(exist_ok=True)
    errors = []
    forward = None
    records = []
    try:
        # A PVC retains request IDs and guards across Locust restarts.
        pods = json.loads(kube.run("get", "pods", "-l", "app=loadgenerator", "--field-selector=status.phase=Running", "-o", "json"))["items"]
        if len(pods) != 1:
            raise RuntimeError("Expected exactly one running Locust Pod")
        pod = pods[0]["metadata"]["name"]
        for name in ("run-config.json", "run-status.json", "requests.jsonl", "fault-events.jsonl", "validation-errors.jsonl"):
            try:
                data = kube.run("exec", pod, "--", "cat", f"/runs/{args.run_id}/{name}")
                (bundle / name).write_text(data)
            except Exception as exc:
                errors.append(f"Missing artifact {name}: {exc}")
        config = json.loads((bundle / "run-config.json").read_text())
        if config.get("benchmark", "fault_tolerance") == "fault_tolerance":
            try:
                (bundle / "fault-deployment.json").write_text(
                    kube.run("exec", pod, "--", "cat", f"/runs/{args.run_id}/fault-deployment.json"))
            except Exception as exc:
                errors.append(f"Missing artifact fault-deployment.json: {exc}")
        if config["run_id"] != args.run_id:
            raise ValueError("Run identity mismatch")
        if not (bundle / "run-status.json").exists():
            raise RuntimeError("Stop the Locust run before collecting; artifacts are still changing")
        config["collection"] = dict(context=context, namespace=args.namespace, temporal_namespace=args.temporal_namespace)
        (bundle / "run-config.json").write_text(json.dumps(config, indent=2))
        errors.extend(kube.snapshot(bundle))
        dispatched = [row for row in read_jsonl(bundle / "requests.jsonl") if row["event"] == "dispatch"]
        fulfillment = config.get("benchmark") == "compensation" and config.get("failure_point", "stock") == "fulfillment"
        if fulfillment:
            dispatched = [dict(row, collect_compensations=True) for row in dispatched]
        if config["system"] == "temporal" and not args.no_port_forward:
            port = args.temporal_address.rsplit(":", 1)[1]
            forward = subprocess.Popen([*kube.command, "port-forward", "svc/temporal-frontend", f"{port}:7233"],
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            import socket
            until = time.monotonic() + 20
            while True:
                try:
                    with socket.create_connection(("127.0.0.1", int(port)), timeout=1):
                        break
                except OSError:
                    if forward.poll() is not None or time.monotonic() > until:
                        raise RuntimeError("Temporal port forwarding failed")
                    time.sleep(0.2)
        while True:
            if config["system"] == "accompanist":
                records = collect_accompanist(kube, args.run_id, bundle, fulfillment)
            else:
                records = []
                asyncio.run(collect_temporal(args.temporal_address, args.temporal_namespace, dispatched, bundle, records))
            known = {row["request_id"] for row in records}
            unresolved = (any(row["status"] == "running" or row.get("compensation_pending") for row in records) or
                          any(row["request_id"] not in known for row in dispatched))
            if not unresolved or time.time() >= config.get("drain_deadline", 0):
                break
            time.sleep(min(2, max(0, config["drain_deadline"] - time.time())))
        if config["system"] == "accompanist":
            try:
                samples = kube.run("exec", "deploy/warehouse-monitor", "--", "cat", "/results/mailbox_samples.csv")
                (bundle / "mailbox_samples.csv").write_text(samples)
            except Exception as exc:
                errors.append(f"Missing mailbox diagnostic: {exc}")
        if config.get("benchmark") == "compensation":
            try:
                stock = int(kube.run("exec", "deploy/db-warehouse", "--", "psql", "-X", "-U", "postgres",
                                     "-d", "warehouse", "-v", "ON_ERROR_STOP=1", "-At", "-c",
                                     f"SELECT stock_quantity FROM products WHERE product_id = {config['product_id']};").strip())
                (bundle / "stock-final.json").write_text(json.dumps({"product_id": config["product_id"],
                                                                         "stock_quantity": stock}, indent=2) + "\n")
            except Exception as exc:
                errors.append(f"Cannot verify final stock: {exc}")
            if config.get("failure_point", "stock") == "fulfillment":
                try:
                    capacity = int(kube.run("exec", "deploy/db-warehouse", "--", "psql", "-X", "-U", "postgres",
                                            "-d", "warehouse", "-v", "ON_ERROR_STOP=1", "-At", "-c",
                                            f"SELECT remaining_capacity FROM fulfillment_capacity WHERE capacity_id = {config['capacity_id']};").strip())
                    (bundle / "capacity-final.json").write_text(json.dumps({"capacity_id": config["capacity_id"],
                                                                             "remaining_capacity": capacity}, indent=2) + "\n")
                except Exception as exc:
                    errors.append(f"Cannot verify final fulfillment capacity: {exc}")
            try:
                loyalty_deployment, loyalty_database = (("db-loyalty", "loyalty") if config["system"] == "accompanist"
                                                         else ("db-warehouse", "warehouse"))
                points = int(kube.run("exec", f"deploy/{loyalty_deployment}", "--", "psql", "-X", "-U", "postgres",
                                      "-d", loyalty_database, "-v", "ON_ERROR_STOP=1", "-At", "-c",
                                      "SELECT points FROM loyalty_points WHERE user_id = 100;").strip())
                (bundle / "loyalty-final.json").write_text(json.dumps({"user_id": 100, "points": points}, indent=2) + "\n")
            except Exception as exc:
                errors.append(f"Cannot verify final loyalty points: {exc}")
    except Exception as exc:
        errors.append(str(exc))
    finally:
        if forward:
            forward.terminate()
            forward.wait(timeout=10)
    (bundle / "executions.json").write_text(json.dumps(records, indent=2))
    manifest = export(bundle, errors)
    # Failed terminal executions do not block another run; unknown/running work does.
    if releasable(manifest, errors):
        guard = kube.run("exec", pod, "--", "cat", "/runs/active-run").strip()
        if guard == args.run_id:
            kube.run("exec", pod, "--", "rm", "/runs/active-run")
    print(bundle)
    return 0 if manifest["valid"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
