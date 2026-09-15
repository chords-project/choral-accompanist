#!/usr/bin/env bash
# Usage: collect_run.sh RUN_UUID [--context CONTEXT] [--namespace NS] [--output DIR]
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! python3 -c 'import temporalio' >/dev/null 2>&1; then
  echo "Error: the active Python environment is missing the 'temporalio' package." >&2
  echo "Activate your benchmark environment and install dependencies from ${script_dir}/requirements.txt." >&2
  exit 1
fi
exec python3 "${script_dir}/collect_run.py" "$@"
