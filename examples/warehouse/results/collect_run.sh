#!/usr/bin/env bash
# Usage: collect_run.sh RUN_UUID [--context CONTEXT] [--namespace NS] [--output DIR]
set -euo pipefail
exec python3 "$(dirname "${BASH_SOURCE[0]}")/collect_run.py" "$@"
