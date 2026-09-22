#!/usr/bin/env python3
"""Plot one or two stock-out bundles entirely offline."""
import argparse
import json
import math
from collections import Counter
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

from export_run_bundle import read_jsonl

PARAMETERS = ("stock_count", "rate", "max_concurrent", "drain_timeout", "product_id")


def cdf(ax, values, label, color, style="-"):
    values = sorted(values)
    if values:
        ax.step([values[0], *values], [0, *[(i + 1) / len(values) for i in range(len(values))]],
                label=label, color=color, linestyle=style)


def load(bundle):
    return (json.loads((bundle / "run-config.json").read_text()),
            json.loads((bundle / "run-manifest.json").read_text()),
            json.loads((bundle / "executions.json").read_text()),
            read_jsonl(bundle / "requests.jsonl"))


def plot(bundles, output):
    data = [load(bundle) for bundle in bundles]
    warnings = []
    if len(data) == 2:
        for key in PARAMETERS:
            if data[0][0].get(key) != data[1][0].get(key):
                warnings.append(f"Incompatible {key}: {data[0][0].get(key)} vs {data[1][0].get(key)}")
    fig, axes = plt.subplots(2, 2, figsize=(13, 9))
    throughput, durable, http, outstanding = axes.flatten()
    for index, (config, manifest, executions, requests) in enumerate(data):
        label = config["system"] + " " + config["run_id"][:8]
        color = f"C{index}"
        if config.get("benchmark") != "compensation":
            warnings.append(label + ": bundle is not a stock-out benchmark")
        if not manifest["valid"]:
            warnings.append(label + ": INVALID — " + "; ".join(manifest["errors"]))
        origin = config.get("started_at", config["created_at"])
        dispatch = {row["request_id"]: row["timestamp"] for row in requests if row["event"] == "dispatch"}
        for status, style in (("completed", "-"), ("failed", ":")):
            rows = [row for row in executions if row["status"] == status and row.get("terminal_at") is not None]
            counts = Counter(math.floor(row["terminal_at"] - origin) for row in rows)
            if counts:
                xs = list(range(min(0, min(counts)), max(counts) + 2))
                throughput.step(xs, [counts.get(x, 0) for x in xs], where="post", color=color,
                                linestyle=style, label=f"{label} {status}")
            latencies = [row["terminal_at"] - dispatch[row["request_id"]] for row in rows
                         if row["request_id"] in dispatch and row["terminal_at"] >= dispatch[row["request_id"]]]
            cdf(durable, latencies, f"{label} {status}", color, style)
        response = {row["request_id"]: row for row in requests if row["event"] == "response"}
        for status, style in (("completed", "-"), ("failed", ":")):
            values = [response[row["request_id"]]["http_latency_ms"] / 1000 for row in executions
                      if row["status"] == status and row["request_id"] in response]
            cdf(http, values, f"{label} {status}", color, style)
        changes = Counter()
        for row in executions:
            if row.get("started_at") is not None:
                changes[math.floor(row["started_at"] - origin)] += 1
                if row.get("terminal_at") is not None:
                    changes[math.floor(row["terminal_at"] - origin)] -= 1
        total = 0
        xs, ys = [], []
        for second, delta in sorted(changes.items()):
            total += delta
            xs.append(second)
            ys.append(total)
        if xs:
            outstanding.step(xs, ys, where="post", color=color, label=label)
    for ax, title, xlabel in ((throughput, "Durable terminal executions per second", "Seconds from run start"),
                              (durable, "Dispatch to durable terminal (CDF)", "Seconds"),
                              (http, "Client HTTP latency by durable outcome (CDF)", "Seconds"),
                              (outstanding, "Unfinished durable executions", "Seconds from run start")):
        ax.set_title(title)
        ax.set_xlabel(xlabel)
        ax.set_ylim(bottom=0)
        ax.grid(alpha=.2)
        if ax.get_legend_handles_labels()[0]:
            ax.legend(fontsize=8)
    fig.suptitle("Warehouse stock-out benchmark" + (" — INVALID / INCOMPATIBLE" if warnings else ""))
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)
    report = {"warnings": warnings, "runs": [item[1] for item in data]}
    output.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    parser.add_argument("comparison", type=Path, nargs="?")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    plot([args.bundle] + ([args.comparison] if args.comparison else []),
         args.output or args.bundle / ("comparison.png" if args.comparison else "compensation.png"))
