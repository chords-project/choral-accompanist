#!/usr/bin/env python3
"""Assemble a self-contained Phase 2 result bundle from copied benchmark artifacts.

Run after copying the Locust and monitor CSVs from their Pods.  The script never
contacts Grafana; any Prometheus range-query JSON and trace IDs are inputs copied
into the bundle alongside the durable database exports.
"""
import argparse, json, shutil, subprocess
from datetime import datetime, timezone
from pathlib import Path

def copy(source, destination):
    if source:
        shutil.copy2(source, destination / Path(source).name)

parser = argparse.ArgumentParser()
parser.add_argument("run_id")
parser.add_argument("--fault-events", required=True)
parser.add_argument("--mailbox-samples", required=True)
parser.add_argument("--locust-history")
parser.add_argument("--locust-stats")
parser.add_argument("--completed-sessions", required=True, help="CSV exported from warehouse session_states")
parser.add_argument("--prometheus-query", action="append", default=[], help="Saved Prometheus range-query JSON")
parser.add_argument("--trace-ids", help="Text file of selected retry trace IDs")
parser.add_argument("--output", default="results")
args = parser.parse_args()
bundle = Path(args.output) / args.run_id
# A collection can be safely repeated after a plotting/dependency failure.
bundle.mkdir(parents=True, exist_ok=True)
for source in [args.fault_events, args.mailbox_samples, args.locust_history, args.locust_stats, args.completed_sessions, args.trace_ids, *args.prometheus_query]: copy(source, bundle)
commit = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
manifest = {"run_id": args.run_id, "created_at_utc": datetime.now(timezone.utc).isoformat(), "commit": commit,
            "metric_interval_seconds": 1, "source_of_truth": {"completions": "warehouse session_states.completed_at", "backlog": "warehouse-monitor mailbox_samples.csv"},
            "success": True, "errors": []}
(bundle / "run-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(bundle)
