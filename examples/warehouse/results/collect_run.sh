#!/usr/bin/env bash
# Collect every required post-run artifact and build the offline result bundle.
# Usage: ./results/collect_run.sh RUN_ID
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 RUN_ID" >&2
  exit 2
fi

RUN_ID=$1
if [[ ! $RUN_ID =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]]; then
  echo "RUN_ID must be a UUID." >&2
  exit 2
fi

WAREHOUSE_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$WAREHOUSE_DIR"
RESULTS_DIR="$WAREHOUSE_DIR/results"
WORK_DIR=$(mktemp -d "$RESULTS_DIR/.collect-${RUN_ID}.XXXXXX")
trap 'rm -rf "$WORK_DIR"' EXIT

pod() {
  local label=$1
  local value
  value=$(kubectl get pods -l "$label" --field-selector=status.phase=Running \
    -o jsonpath='{.items[0].metadata.name}')
  if [[ -z $value ]]; then
    echo "No running Pod found for label: $label" >&2
    exit 1
  fi
  printf '%s' "$value"
}

LOADGEN_POD=$(pod app=loadgenerator)
MONITOR_POD=$(pod app=warehouse-monitor)

echo "Collecting run $RUN_ID from $LOADGEN_POD and $MONITOR_POD"
kubectl cp "$LOADGEN_POD:/tmp/fault_events.csv" "$WORK_DIR/fault_events.csv"
kubectl cp "$MONITOR_POD:/results/mailbox_samples.csv" "$WORK_DIR/mailbox_samples.csv"

# Locust creates these after the test stops. They are included in the bundle as
# supporting request-level evidence, while warehouse completion rows remain the
# source of truth for logical throughput.
kubectl cp "$LOADGEN_POD:/tmp/webshop_stats_history.csv" "$WORK_DIR/locust_stats_history.csv"
kubectl cp "$LOADGEN_POD:/tmp/webshop_stats.csv" "$WORK_DIR/locust_stats.csv"

RECORDED_RUN_ID=$(awk -F, 'NR == 2 { print $1 }' "$WORK_DIR/fault_events.csv")
if [[ $RECORDED_RUN_ID != "$RUN_ID" ]]; then
  echo "fault_events.csv belongs to run '$RECORDED_RUN_ID', not '$RUN_ID'." >&2
  exit 1
fi

# --csv writes a header even if no sessions completed; that is useful evidence
# for failed runs and lets the plotting/reporting step remain deterministic.
kubectl exec deploy/db-warehouse -- psql -U postgres -d warehouse --csv \
  -c "SELECT session_id, choreography, run_id, started_at, completed_at, attempt_count, restart_count
      FROM session_states
      WHERE run_id = '$RUN_ID'::uuid AND session_state = 'completed'
      ORDER BY completed_at;" > "$WORK_DIR/completed_sessions.csv"

python3 results/export_run_bundle.py "$RUN_ID" \
  --fault-events "$WORK_DIR/fault_events.csv" \
  --mailbox-samples "$WORK_DIR/mailbox_samples.csv" \
  --completed-sessions "$WORK_DIR/completed_sessions.csv" \
  --locust-history "$WORK_DIR/locust_stats_history.csv" \
  --locust-stats "$WORK_DIR/locust_stats.csv"

if python3 -c 'import matplotlib' 2>/dev/null; then
  python3 results/plot_run.py "$RESULTS_DIR/$RUN_ID"
  echo "Collected bundle and generated $RESULTS_DIR/$RUN_ID/fault-tolerance.png"
else
  echo "Collected bundle at $RESULTS_DIR/$RUN_ID" >&2
  echo "Install matplotlib to generate the figure: python3 -m pip install matplotlib" >&2
fi
