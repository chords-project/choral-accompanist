#!/usr/bin/env bash
# Run before starting the benchmark; uses Warehouse's pod network and DNS configuration.
# Usage: bash capture_dns.sh CONTEXT OUTPUT_FILE [NAMESPACE] [SECONDS]
set -euo pipefail
context=${1:?Specify the Kubernetes context}
output=${2:?Specify an output file}
namespace=${3:-warehouse-benchmark}
duration=${4:-420}
if ! [[ "$duration" =~ ^[1-9][0-9]*$ ]]; then
  echo 'SECONDS must be a positive integer' >&2
  exit 1
fi
kube=(kubectl --context "$context" --namespace "$namespace")
pod=$("${kube[@]}" get pods -l app=warehouse --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}')
test -n "$pod"
container="dns-capture-$(date +%s)"
mkdir -p "$(dirname "$output")"
printf 'context=%s namespace=%s pod=%s container=%s duration=%s\n' "$context" "$namespace" "$pod" "$container" "$duration" > "$output"
# An ephemeral BusyBox container shares the existing pod network. Every nslookup
# starts a new process, so this observes cluster DNS without the application's JVM cache.
# It exits after the requested duration; its terminated entry stays until pod replacement.
"${kube[@]}" debug "pod/$pod" --container="$container" --image=busybox:1.37.0 \
  --profile=general --attach=true -- sh -c '
    cat /etc/resolv.conf
    remaining=$1
    while [ "$remaining" -gt 0 ]; do
      date -u +%Y-%m-%dT%H:%M:%SZ
      nslookup payment
      echo "lookup_exit=$?"
      remaining=$((remaining - 1))
      sleep 1
    done
  ' sh "$duration" 2>&1 | tee -a "$output"
