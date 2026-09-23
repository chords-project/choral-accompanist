#!/usr/bin/env python3
"""Validate and summarize a common bundle without contacting either system."""
import argparse
import json
import math
from pathlib import Path


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]


def export(bundle, collection_errors=()):
    errors = list(collection_errors)
    def read(name, default):
        try:
            return read_jsonl(bundle / name) if name.endswith("jsonl") else json.loads((bundle / name).read_text())
        except (OSError, ValueError) as exc:
            errors.append(f"Missing or malformed {name}: {exc}")
            return default
    config = read("run-config.json", {})
    status = read("run-status.json", {})
    requests = read("requests.jsonl", [])
    events = read("fault-events.jsonl", [])
    errors.extend(row["error"] for row in read("validation-errors.jsonl", []))
    executions = read("executions.json", [])
    scheduled = [row for row in requests if row["event"] == "scheduled"]
    compensation_run = config.get("benchmark") == "compensation"
    failure_point = config.get("failure_point", "stock")
    expected = config.get("request_count", 0) if compensation_run else math.ceil(config.get("rate", 0) * config.get("duration", 0))
    if len(scheduled) != expected:
        errors.append(f"Expected {expected} scheduled arrivals, recorded {len(scheduled)}")
    if any(row["event"] == "missed" for row in requests):
        errors.append("Requested arrival rate was not met")
    dispatched = {row["request_id"] for row in requests if row["event"] == "dispatch"}
    known = {row["request_id"] for row in executions}
    unknown = sorted(dispatched - known)
    running = [row for row in executions if row["status"] == "running"]
    failures = [row for row in executions if row["status"] == "failed"]
    pending_compensations = [row for row in failures if row.get("compensation_pending")]
    complete = not unknown and not running and not pending_compensations and bool(status.get("stopped"))
    if unknown:
        errors.append(f"{len(unknown)} dispatched requests have no durable execution record (acceptance unknown)")
    if running:
        errors.append(f"Incomplete drain: {len(running)} unfinished executions")
    if pending_compensations:
        errors.append(f"Incomplete drain: {len(pending_compensations)} executions are not fully compensated")
    if any(row.get("started_at") is None for row in executions):
        errors.append("Execution records missing durable start timestamps")
    if not status.get("restored") and not compensation_run:
        errors.append("Payment restoration is unverified")
    recovery = {"status": "unobserved", "seconds": None}
    compensation_drain = {"status": "unobserved", "seconds": None, "orders": 0}
    if compensation_run:
        count = config.get("resource_count", config.get("stock_count"))
        if not isinstance(count, int) or count < 1 or expected != 2 * count:
            errors.append("Invalid resource-count or request-count configuration")
        final_stock = read("stock-final.json", {})
        if failure_point == "stock":
            if config.get("initial_stock") != count:
                errors.append("Initial stock was not verified")
            if final_stock.get("stock_quantity") != 0 or final_stock.get("product_id") != config.get("product_id"):
                errors.append("Final stock is not zero or product identity differs")
        elif failure_point == "fulfillment":
            if config.get("initial_fulfillment_capacity") != count:
                errors.append("Initial fulfillment capacity was not verified")
            expected_stock = config.get("initial_stock", 1_000_000_000) - count
            if (final_stock.get("stock_quantity") != expected_stock or
                    final_stock.get("product_id") != config.get("product_id")):
                errors.append("Final stock does not reflect successful orders and compensated reservations")
            final_capacity = read("capacity-final.json", {})
            if (final_capacity.get("remaining_capacity") != 0 or
                    final_capacity.get("capacity_id") != config.get("capacity_id")):
                errors.append("Final fulfillment capacity is not zero or capacity identity differs")
            timed = [row for row in failures if row.get("compensation_started_at") is not None and
                     row.get("compensation_completed_at") is not None]
            if len(timed) != len(failures):
                errors.append("Failed executions are missing complete compensation timing evidence")
                compensation_drain = {"status": "unresolved", "seconds": None, "orders": len(timed)}
            else:
                compensation_drain = {
                    "status": "complete",
                    "seconds": max(0, max((row["compensation_completed_at"] for row in timed), default=0) -
                                   min((row["compensation_started_at"] for row in timed), default=0)),
                    "orders": len(timed),
                }
        else:
            errors.append(f"Unknown compensation failure point: {failure_point}")
        if config.get("schema_version", 1) >= 4:
            loyalty = read("loyalty-final.json", {})
            initial_points = config.get("initial_loyalty_points")
            if (not isinstance(initial_points, int) or loyalty.get("user_id") != 100 or
                    loyalty.get("points") != initial_points + count):
                errors.append("Final loyalty points do not reflect successful orders and durable compensations")
        if len(dispatched) != expected:
            errors.append(f"Expected {expected} dispatched requests, recorded {len(dispatched)}")
        if sum(row["status"] == "completed" for row in executions) != count:
            errors.append(f"Expected {count} successful executions")
        if len(failures) != count:
            errors.append(f"Expected {count} failed executions")
        if len(known) != len(executions) or known != dispatched:
            errors.append("Execution correlations do not match dispatched requests")
    else:
        unavailable = next((row["timestamp"] for row in events if row["phase"] == "target-unavailable"), None)
        ready = next((row["timestamp"] for row in events if row["phase"] == "target-ready"), None)
        if unavailable is None or ready is None:
            errors.append("Missing observed outage or readiness")
        else:
            if ready < unavailable:
                errors.append("Readiness precedes unavailability")
            if ready - unavailable + 1 < config.get("fault_duration", 0):
                errors.append("Observed outage was shorter than configured")
            cohort = [row for row in executions if row["started_at"] is not None and row["started_at"] <= ready
                      and (row["terminal_at"] is None or row["terminal_at"] > ready)]
            if unknown or any(row["started_at"] is None for row in executions) or any(row["status"] == "running" for row in cohort):
                recovery = {"status": "unresolved", "seconds": None}
            elif any(row["status"] != "completed" for row in cohort):
                recovery = {"status": "failed", "seconds": None}
            else:
                recovery = {"status": "complete", "seconds": max((row["terminal_at"] - ready for row in cohort), default=0)}
            recovery["orders"] = len(cohort)
    if failures and not compensation_run:
        errors.append(f"{len(failures)} durable executions failed")
    manifest = dict(schema_version=config.get("schema_version", 1), run_id=config.get("run_id"), system=config.get("system"),
                    valid=not errors, complete=complete, restored=bool(status.get("restored")),
                    errors=sorted(set(errors)), unknown_request_ids=unknown, unfinished=len(running),
                    successes=sum(row["status"] == "completed" for row in executions), failures=len(failures),
                    outage_cohort_recovery=recovery, compensation_drain=compensation_drain,
                    benchmark=config.get("benchmark", "fault_tolerance"))
    (bundle / "run-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    # Keep collection failures when revalidating offline.
    previous = json.loads((args.bundle / "run-manifest.json").read_text()) if (args.bundle / "run-manifest.json").exists() else {}
    print(json.dumps(export(args.bundle, previous.get("errors", [])), indent=2))
