#!/usr/bin/env python3
"""Create an offline fault-tolerance plot from a collected result bundle."""
import argparse
import csv
import math
from collections import Counter
from datetime import datetime, timedelta
from pathlib import Path

import matplotlib.dates as mdates
import matplotlib.pyplot as plt


def parse_timestamp(value):
    """Parse Postgres and RFC 3339 timestamps as timezone-aware datetimes."""
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


BUCKET_SECONDS = 10


parser = argparse.ArgumentParser()
parser.add_argument("bundle")
args = parser.parse_args()
bundle = Path(args.bundle)
completed = bundle / "completed_sessions.csv"
samples = bundle / "mailbox_samples.csv"
events = bundle / "fault_events.csv"
for artifact in (completed, samples, events):
    if not artifact.is_file():
        raise FileNotFoundError(f"Bundle is missing required artifact: {artifact}")

with events.open(newline="") as file:
    fault_events = list(csv.DictReader(file))
if not fault_events:
    raise ValueError("fault_events.csv is empty")
run_id = fault_events[0]["run_id"]
event_times = {row["phase"]: parse_timestamp(row["timestamp_utc"]) for row in fault_events}

completion_times = []
with completed.open(newline="") as file:
    for row in csv.DictReader(file):
        if row.get("completed_at"):
            completion_times.append(parse_timestamp(row["completed_at"]))

# The warehouse outbox holds messages that cannot be delivered while payment is
# unavailable. Keep every one-second sample; reducing them by minute hides peaks.
backlog_samples = []
with samples.open(newline="") as file:
    for row in csv.DictReader(file):
        if (row.get("run_id") == run_id and row.get("service") == "warehouse"
                and row.get("pending_outbox") not in (None, "")):
            backlog_samples.append((parse_timestamp(row["observed_at_utc"]), int(row["pending_outbox"])))
if not backlog_samples:
    raise ValueError(f"No warehouse backlog samples found for run {run_id}")
backlog_samples.sort()

timeline_start = event_times.get("test-start", backlog_samples[0][0])
timeline_end = max(backlog_samples[-1][0], max(completion_times, default=timeline_start))
bucket_count = max(1, math.ceil((timeline_end - timeline_start).total_seconds() / BUCKET_SECONDS))
completion_counts = Counter(
    min(
        bucket_count - 1,
        int((completed_at - timeline_start).total_seconds() // BUCKET_SECONDS),
    )
    for completed_at in completion_times
    if timeline_start <= completed_at <= timeline_end
)
bucket_starts = [
    timeline_start + timedelta(seconds=index * BUCKET_SECONDS)
    for index in range(bucket_count)
]
completion_rates = [completion_counts[index] / BUCKET_SECONDS for index in range(bucket_count)]

fig, left = plt.subplots(figsize=(11, 5.5))
right = left.twinx()

unavailable = event_times.get("target-unavailable")
ready = event_times.get("target-ready")
if unavailable and ready:
    left.axvspan(unavailable, ready, color="0.55", alpha=0.18, zorder=0)
    left.axvline(unavailable, color="0.45", linestyle="--", linewidth=1, zorder=1)
    left.axvline(ready, color="0.45", linestyle="--", linewidth=1, zorder=1)

throughput_line = left.step(
    bucket_starts + [timeline_end],
    completion_rates + [completion_rates[-1]],
    where="post",
    color="tab:blue",
    linewidth=2,
    label=f"completed warehouse choreographies/s ({BUCKET_SECONDS} s buckets)",
    zorder=3,
)
backlog_line = right.plot(
    [sample[0] for sample in backlog_samples],
    [sample[1] for sample in backlog_samples],
    color="tab:red",
    linewidth=1.5,
    label="warehouse pending outbox",
    zorder=2,
)

left.set_xlim(timeline_start, timeline_end)
left.set_ylim(bottom=0)
right.set_ylim(bottom=0)
left.set_ylabel("completed choreographies/s")
right.set_ylabel("pending outbox messages")
left.set_xlabel("UTC time")
left.grid(axis="y", alpha=0.25)
left.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M", tz=timeline_start.tzinfo))
left.xaxis.set_major_locator(mdates.MinuteLocator(interval=1, tz=timeline_start.tzinfo))

handles = throughput_line + backlog_line
labels = [handle.get_label() for handle in handles]
if unavailable and ready:
    handles.insert(0, plt.Rectangle((0, 0), 1, 1, color="0.55", alpha=0.18))
    labels.insert(0, "payment unavailable")
left.legend(handles, labels, loc="upper left")
left.set_title(f"Fault-tolerance benchmark ({run_id})")
fig.autofmt_xdate()
fig.tight_layout()
fig.savefig(bundle / "fault-tolerance.png", dpi=200)
