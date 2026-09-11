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
    expected = math.ceil(config.get("rate", 0) * config.get("duration", 0))
    if len(scheduled) != expected:
        errors.append(f"Expected {expected} scheduled arrivals, recorded {len(scheduled)}")
    if any(row["event"] == "missed" for row in requests):
        errors.append("Requested arrival rate was not met")
    dispatched = {row["request_id"] for row in requests if row["event"] == "dispatch"}
    known = {row["request_id"] for row in executions}
    unknown = sorted(dispatched - known)
    running = [row for row in executions if row["status"] == "running"]
    failures = [row for row in executions if row["status"] == "failed"]
    complete = not unknown and not running and bool(status.get("stopped"))
    if unknown:
        errors.append(f"{len(unknown)} dispatched requests have no durable execution record (acceptance unknown)")
    if running:
        errors.append(f"Incomplete drain: {len(running)} unfinished executions")
    if any(row.get("started_at") is None for row in executions):
        errors.append("Execution records missing durable start timestamps")
    if not status.get("restored"):
        errors.append("Payment restoration is unverified")
    unavailable = next((row["timestamp"] for row in events if row["phase"] == "target-unavailable"), None)
    ready = next((row["timestamp"] for row in events if row["phase"] == "target-ready"), None)
    recovery = {"status": "unobserved", "seconds": None}
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
    if failures:
        errors.append(f"{len(failures)} durable executions failed")
    manifest = dict(schema_version=1, run_id=config.get("run_id"), system=config.get("system"),
                    valid=not errors, complete=complete, restored=bool(status.get("restored")),
                    errors=sorted(set(errors)), unknown_request_ids=unknown, unfinished=len(running),
                    successes=sum(row["status"] == "completed" for row in executions), failures=len(failures),
                    outage_cohort_recovery=recovery)
    (bundle / "run-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()
    # Keep collection failures when revalidating offline.
    previous = json.loads((args.bundle / "run-manifest.json").read_text()) if (args.bundle / "run-manifest.json").exists() else {}
    print(json.dumps(export(args.bundle, previous.get("errors", [])), indent=2))
