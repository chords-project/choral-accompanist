#!/usr/bin/env python3
"""Plot one or two compensation benchmark bundles entirely offline."""
import argparse
import json
import math
from collections import Counter
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.ticker import FuncFormatter

from export_run_bundle import read_jsonl

PARAMETERS = ("failure_point", "resource_count", "rate", "max_concurrent", "drain_timeout")


def parameter(config, key):
    if key == "failure_point":
        return config.get(key, "stock")
    if key == "resource_count":
        return config.get(key, config.get("stock_count"))
    return config.get(key)


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


def recorded_summary(bundle):
    """Return a benchmark summary only when the collected evidence is complete."""
    try:
        config, manifest, executions, _ = load(bundle)
        stock = json.loads((bundle / "stock-final.json").read_text())
    except (OSError, ValueError, KeyError):
        return None
    if (config.get("benchmark") != "compensation" or
            not manifest.get("valid") or not manifest.get("complete") or
            manifest.get("run_id") != config.get("run_id") or
            manifest.get("system") != config.get("system") or
            stock.get("product_id") != config.get("product_id") or
            not isinstance(stock.get("stock_quantity"), int)):
        return None
    counts = Counter(row.get("status") for row in executions)
    successes, failures = counts["completed"], counts["failed"]
    if (successes != manifest.get("successes") or failures != manifest.get("failures") or
            successes + failures != config.get("request_count")):
        return None
    failure_point = config.get("failure_point", "stock")
    if failure_point == "fulfillment":
        try:
            capacity = json.loads((bundle / "capacity-final.json").read_text())
        except (OSError, ValueError):
            return None
        if capacity.get("capacity_id") != config.get("capacity_id"):
            return None
        resource = ("fulfillment capacity", capacity.get("remaining_capacity"))
    else:
        resource = ("stock", stock["stock_quantity"])
    return config["system"], successes, failures, resource, manifest.get("compensation_drain")


def plot(bundles, output):
    data = [load(bundle) for bundle in bundles]
    warnings = []
    if len(data) == 2:
        for key in PARAMETERS:
            first, second = parameter(data[0][0], key), parameter(data[1][0], key)
            if first != second:
                warnings.append(f"Incompatible {key}: {first} vs {second}")
    fig, axes = plt.subplots(2, 3, figsize=(18, 9))
    throughput, durable, compensation, http, outstanding, compensation_backlog = axes.flatten()
    for index, (config, manifest, executions, requests) in enumerate(data):
        label = config["system"] + " " + config["run_id"][:8]
        color = f"C{index}"
        if config.get("benchmark") != "compensation":
            warnings.append(label + ": bundle is not a compensation benchmark")
        if not manifest["valid"]:
            warnings.append(label + ": INVALID — " + "; ".join(manifest["errors"]))
        origin = config.get("started_at", config["created_at"])
        dispatch = {row["request_id"]: row["timestamp"] for row in requests if row["event"] == "dispatch"}
        for status, style in (("completed", "-"), ("failed", ":")):
            rows = [row for row in executions if row["status"] == status and row.get("terminal_at") is not None]
            counts = Counter(math.floor(row["terminal_at"] - origin) for row in rows)
            if counts:
                xs = list(range(min(0, min(counts)), max(counts) + 2))
                direction = -1 if len(data) == 2 and index == 1 else 1
                throughput.step(xs, [direction * counts.get(x, 0) for x in xs], where="post", color=color,
                                linestyle=style, label=f"{label} {status}")
            latencies = [row["terminal_at"] - dispatch[row["request_id"]] for row in rows
                         if row["request_id"] in dispatch and row["terminal_at"] >= dispatch[row["request_id"]]]
            cdf(durable, latencies, f"{label} {status}", color, style)
        compensation_values = [row["compensation_completed_at"] - row["compensation_started_at"]
                               for row in executions if row["status"] == "failed" and
                               row.get("compensation_started_at") is not None and
                               row.get("compensation_completed_at") is not None]
        cdf(compensation, compensation_values, label, color)
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
        changes = Counter()
        for row in executions:
            if row.get("compensation_started_at") is not None:
                changes[math.floor(row["compensation_started_at"] - origin)] += 1
            if row.get("compensation_completed_at") is not None:
                changes[math.floor(row["compensation_completed_at"] - origin)] -= 1
        total = 0
        xs, ys = [], []
        for second, delta in sorted(changes.items()):
            total += delta
            xs.append(second)
            ys.append(total)
        if xs:
            compensation_backlog.step(xs, ys, where="post", color=color, label=label)
    if len(data) == 2:
        throughput.axhline(0, color="0.35", linewidth=.8)
        throughput.yaxis.set_major_formatter(FuncFormatter(lambda value, _: f"{abs(value):g}"))
        throughput.set_ylabel(f"{data[0][0]['system']} ↑  |  {data[1][0]['system']} ↓\nExecutions/s (absolute scale)")
    else:
        throughput.set_ylabel("Executions/s")
    for ax, title, xlabel in ((throughput, "Durable terminal executions per second", "Seconds from run start"),
                              (durable, "Dispatch to durable terminal (CDF)", "Seconds"),
                              (compensation, "Final-step rejection to full compensation (CDF)", "Seconds"),
                              (http, "Client HTTP latency by durable outcome (CDF)", "Seconds"),
                              (outstanding, "Unfinished durable executions", "Seconds from run start"),
                              (compensation_backlog, "Compensations in progress", "Seconds from run start")):
        ax.set_title(title)
        ax.set_xlabel(xlabel)
        if ax is not throughput or len(data) == 1:
            ax.set_ylim(bottom=0)
        ax.grid(alpha=.2)
        if ax.get_legend_handles_labels()[0]:
            ax.legend(fontsize=8)
    fig.suptitle("Warehouse compensation benchmark" + (" — INVALID / INCOMPATIBLE" if warnings else ""))
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
    bundles = [args.bundle] + ([args.comparison] if args.comparison else [])
    plot(bundles, args.output or args.bundle / ("comparison.png" if args.comparison else "compensation.png"))
    for bundle in bundles:
        summary = recorded_summary(bundle)
        if summary is not None:
            system, successes, failures, resource, drain = summary
            drain_text = (f", compensation drain={drain['seconds']:.3f}s"
                          if drain and drain.get("status") == "complete" else "")
            print(f"{system}: succeeded={successes}, failed={failures}, final {resource[0]}={resource[1]}{drain_text}")
