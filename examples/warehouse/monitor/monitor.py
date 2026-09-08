"""Outage-safe, read-only PostgreSQL mailbox/session sampler."""
import csv, os, time
from pathlib import Path
import psycopg
from opentelemetry import metrics
from opentelemetry.exporter.otlp.proto.grpc.metric_exporter import OTLPMetricExporter
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource

SERVICES = os.getenv("MONITOR_SERVICES", "warehouse,payment,loyalty").split(",")
INTERVAL = float(os.getenv("MONITOR_INTERVAL_SECONDS", "1"))
SAMPLES = Path(os.getenv("MONITOR_SAMPLES_PATH", "/results/mailbox_samples.csv"))
provider = MeterProvider(resource=Resource.create({"service.name": "warehouse-monitor"}),
    metric_readers=[PeriodicExportingMetricReader(OTLPMetricExporter(endpoint=os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://lgtm:4317"), insecure=True), export_interval_millis=1000)])
metrics.set_meter_provider(provider)
meter = metrics.get_meter("choral.accompanist")
latest = {"pending": [], "total": [], "inbox": [], "sessions": []}
def callback(name):
    return lambda options: [metrics.Observation(value, attributes) for value, attributes in latest[name]]
meter.create_observable_gauge("accompanist.mailbox.outbox.pending", callbacks=[callback("pending")], unit="{message}")
meter.create_observable_gauge("accompanist.mailbox.outbox.total", callbacks=[callback("total")], unit="{message}")
meter.create_observable_gauge("accompanist.mailbox.inbox.total", callbacks=[callback("inbox")], unit="{message}")
meter.create_observable_gauge("accompanist.sessions.pending", callbacks=[callback("sessions")], unit="{session}")
errors = meter.create_counter("accompanist.monitor.observation_errors", unit="{error}")

def url(service): return os.getenv("POSTGRES_%s_URL" % service.upper(), "postgresql://postgres:postgres@db-%s:5432/%s" % (service, service))
def rows(service):
    with psycopg.connect(url(service), connect_timeout=2, options="-c statement_timeout=2000") as db:
        with db.cursor() as cur:
            # Session rows outlive mailbox cleanup, so they define the run IDs for which
            # mailbox gauges must continue to report an explicit zero.
            cur.execute("SELECT DISTINCT run_id::text FROM session_states")
            run_ids = {run_id for run_id, in cur.fetchall()}
            cur.execute("""
                SELECT ss.run_id::text, COUNT(*) FILTER (WHERE NOT o.acknowledged), COUNT(*)
                FROM outbox o LEFT JOIN session_states ss ON o.session_id = ss.session_id
                GROUP BY ss.run_id
            """)
            outbox_rows = {run_id: (pending, total) for run_id, pending, total in cur.fetchall()}
            cur.execute("""
                SELECT ss.run_id::text, COUNT(*) FROM inbox i
                LEFT JOIN session_states ss ON i.session_id = ss.session_id GROUP BY ss.run_id
            """)
            inbox_rows = dict(cur.fetchall())
            cur.execute("SELECT run_id::text, session_state::text, COUNT(*) FROM session_states WHERE session_state IN ('started','restart') GROUP BY run_id, session_state")
            return run_ids | outbox_rows.keys() | inbox_rows.keys(), outbox_rows, inbox_rows, cur.fetchall()

SAMPLES.parent.mkdir(parents=True, exist_ok=True)
new = not SAMPLES.exists()
with SAMPLES.open("a", newline="") as output:
    writer = csv.writer(output)
    if new: writer.writerow(["observed_at_utc", "service", "run_id", "pending_outbox", "total_outbox", "inbox_total", "state", "session_count"])
    while True:
        latest = {"pending": [], "total": [], "inbox": [], "sessions": []}
        for service in SERVICES:
            attrs = {"database.owner": service}
            try:
                run_ids, outbox_rows, inbox_rows, state_rows = rows(service)
                # A successful database observation is authoritative. Keep reporting every
                # known run after mailbox cleanup so Prometheus receives a terminal zero.
                if not run_ids: run_ids = {None}
                for run_id in run_ids:
                    p, t = outbox_rows.get(run_id, (0, 0))
                    a = attrs | ({"benchmark.run_id": run_id} if run_id else {})
                    i = inbox_rows.get(run_id, 0)
                    latest["pending"].append((p, a)); latest["total"].append((t, a)); latest["inbox"].append((i, a))
                    writer.writerow([time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), service, run_id or "", p, t, i, "", 0])
                for run_id, state, count in state_rows:
                    a = attrs | ({"benchmark.run_id": run_id} if run_id else {})
                    latest["sessions"].append((count, a | {"state": state}))
                    writer.writerow([time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), service, run_id or "", "", "", "", state, count])
            except Exception as exc:
                errors.add(1, attrs | {"error.category": type(exc).__name__})
        output.flush()
        time.sleep(INTERVAL)
