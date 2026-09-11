#!/usr/bin/env python3
"""Plot one bundle or compare two entirely offline, aligned to observed outage."""
import argparse
import csv
import json
import math
from collections import Counter
from datetime import datetime
from pathlib import Path
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from export_run_bundle import read_jsonl

PARAMETERS = ("rate", "duration", "fault_after", "fault_duration", "drain_timeout", "max_concurrent")


def bucket_counts(values, origin):
    return Counter(math.floor(value - origin) for value in values)


def mailbox_series(stream, run_id, origin):
    """Return warehouse mailbox samples, excluding interleaved session-state rows."""
    rows = [row for row in csv.DictReader(stream)
            if row["run_id"] == run_id and row["service"] == "warehouse"
            and row["pending_outbox"] != ""]
    return ([datetime.fromisoformat(row["observed_at_utc"].replace("Z", "+00:00")).timestamp() - origin
             for row in rows],
            [int(row["pending_outbox"]) for row in rows])


def load(bundle):
    return (json.loads((bundle / "run-config.json").read_text()),
            json.loads((bundle / "run-manifest.json").read_text()),
            json.loads((bundle / "executions.json").read_text()),
            read_jsonl(bundle / "requests.jsonl"), read_jsonl(bundle / "fault-events.jsonl"))


def plot(bundles, output):
    data = [load(bundle) for bundle in bundles]
    warnings = []
    if len(data) == 2:
        for key in PARAMETERS:
            if data[0][0].get(key) != data[1][0].get(key):
                warnings.append(f"Incompatible {key}: {data[0][0].get(key)} vs {data[1][0].get(key)}")
    fig, axes = plt.subplots(3, 2, figsize=(14, 11))
    axes = axes.flatten()
    for index, (config, manifest, executions, requests, events) in enumerate(data):
        color = f"C{index}"
        label = config["system"] + " " + config["run_id"][:8]
        if not manifest["valid"]:
            warnings.append(label + ": INVALID — " + "; ".join(manifest["errors"]))
        phases = {row["phase"]: row["timestamp"] for row in events}
        origin = phases.get("target-unavailable", config.get("started_at", config["created_at"]))
        if "target-unavailable" not in phases:
            warnings.append(label + ": no observed unavailability; aligned to run start")
        ready = phases.get("target-ready")
        if ready:
            for ax in axes[:3]:
                ax.axvspan(0, ready - origin, color=color, alpha=0.1, label=label + " outage")
        def rate_plot(ax, values, name, style="-", marker=None, marker_offset=0):
            counts = bucket_counts(values, origin)
            if counts:
                xs = list(range(min(math.floor(config.get("started_at", origin) - origin), min(counts)),
                                max(math.ceil(phases.get("test-stop", origin) - origin), max(counts) + 1) + 1))
                ax.step(xs, [counts.get(x, 0) for x in xs], where="post", color=color,
                        linestyle=style, marker=marker, markevery=(marker_offset, 24),
                        markersize=4, alpha=.85, label=label + " " + name)
        # These normally coincide. Staggered markers make all four series identifiable
        # without shifting the data or implying a difference that is not present.
        rate_plot(axes[0], [r["scheduled_at"] for r in requests if r["event"] == "scheduled"],
                  "scheduled", "--", "x", 6 + index * 12)
        rate_plot(axes[0], [r["timestamp"] for r in requests if r["event"] == "dispatch"],
                  "actual", "-", "o" if index == 0 else "s", index * 12)
        rate_plot(axes[1], [r["terminal_at"] for r in executions if r["status"] == "completed"], "successes")
        rate_plot(axes[1], [r["terminal_at"] for r in executions if r["status"] == "failed" and r["terminal_at"] is not None], "failures", ":")
        changes = Counter()
        for row in executions:
            if row["started_at"] is not None:
                changes[row["started_at"] - origin] += 1
                if row["terminal_at"] is not None:
                    changes[row["terminal_at"] - origin] -= 1
        outstanding, xs, ys = 0, [], []
        for at, delta in sorted(changes.items()):
            outstanding += delta
            xs.append(at)
            ys.append(outstanding)
        if xs:
            xs.append(max(xs[-1], phases.get("test-stop", origin) - origin))
            ys.append(outstanding)
        axes[2].step(xs, ys, where="post", color=color, label=label)
        latencies = sorted(row["terminal_at"] - row["started_at"] for row in executions
                           if row["status"] == "completed" and row["terminal_at"] is not None and row["started_at"] is not None)
        if latencies:
            axes[3].step([latencies[0], *latencies], [0, *[(i + 1) / len(latencies) for i in range(len(latencies))]], label=label, color=color)
        http = sorted(row["http_latency_ms"] / 1000 for row in requests if row["event"] == "response")
        if http:
            axes[4].step([http[0], *http], [0, *[(i + 1) / len(http) for i in range(len(http))]], label=label, color=color)
        samples = bundles[index] / "mailbox_samples.csv"
        if config["system"] == "accompanist" and samples.exists():
            with samples.open() as stream:
                xs, ys = mailbox_series(stream, config["run_id"], origin)
            axes[5].plot(xs, ys, label=label)
    titles = ["Scheduled / actual arrivals per second (coincident lines overlap)", "Durable terminal executions per second",
              "Unfinished accepted orders (starts − all terminals)", "Durable end-to-end execution latency (CDF)",
              "Client-observed HTTP latency, including failures (CDF)", "Accompanist mailbox diagnostic (not a Temporal queue metric)"]
    for i, ax in enumerate(axes):
        ax.set_title(titles[i], fontsize=10)
        ax.set_xlabel("Latency (s)" if i in (3, 4) else "Seconds from observed payment unavailability")
        ax.set_ylim(bottom=0)
        ax.grid(alpha=0.2)
        if ax.get_legend_handles_labels()[0]:
            ax.legend(fontsize=7)
    if not axes[5].lines:
        axes[5].text(.5, .5, "No Accompanist mailbox samples supplied", ha="center", transform=axes[5].transAxes)
    fig.suptitle("Recovery benchmark" + (" — INVALID / INCOMPATIBLE: see adjacent JSON report" if warnings else ""))
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)
    report = {"warnings": warnings, "runs": [d[1] for d in data]}
    output.with_suffix(".json").write_text(json.dumps(report, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("comparison", type=Path, nargs="?")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    plot([args.bundle] + ([args.comparison] if args.comparison else []),
         args.output or args.bundle / ("comparison.png" if args.comparison else "fault-tolerance.png"))
