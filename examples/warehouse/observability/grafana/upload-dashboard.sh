#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dashboard_file="${1:-${script_dir}/dashboards/accompanist-overview.json}"
grafana_url="${GRAFANA_URL:-http://localhost:3000}"
grafana_url="${grafana_url%/}"

if [[ ! -f "${dashboard_file}" ]]; then
  echo "Dashboard does not exist: ${dashboard_file}" >&2
  exit 1
fi

for command in curl jq; do
  if ! command -v "${command}" >/dev/null 2>&1; then
    echo "Required command is not installed: ${command}" >&2
    exit 1
  fi
done

curl_args=(--fail-with-body --silent --show-error)
if [[ -n "${GRAFANA_TOKEN:-}" ]]; then
  curl_args+=(--header "Authorization: Bearer ${GRAFANA_TOKEN}")
elif [[ -n "${GRAFANA_USER:-}" || -n "${GRAFANA_PASSWORD:-}" ]]; then
  curl_args+=(--user "${GRAFANA_USER:-admin}:${GRAFANA_PASSWORD:-admin}")
fi

folders="$(curl "${curl_args[@]}" "${grafana_url}/api/search?type=dash-folder&query=Accompanist")"
folder_exists="$(jq -r 'any(.[]; .uid == "accompanist")' <<<"${folders}")"
if [[ "${folder_exists}" != "true" ]]; then
  curl "${curl_args[@]}" \
    --header 'Content-Type: application/json' \
    --request POST \
    --data '{"uid":"accompanist","title":"Accompanist"}' \
    "${grafana_url}/api/folders" >/dev/null
fi

payload="$(mktemp)"
trap 'rm -f "${payload}"' EXIT
dashboard_uid="$(jq -r '.uid // empty' "${dashboard_file}")"
if [[ ! "${dashboard_uid}" =~ ^[A-Za-z0-9_-]+$ ]]; then
  echo "Dashboard must contain a URL-safe uid: ${dashboard_file}" >&2
  exit 1
fi

jq -n --slurpfile dashboard "${dashboard_file}" \
  '{
    kind: "Dashboard",
    apiVersion: "dashboard.grafana.app/v1",
    metadata: {
      name: $dashboard[0].uid,
      annotations: {
        "grafana.app/folder": "accompanist",
        "grafana.app/message": "Updated from repository JSON"
      }
    },
    spec: $dashboard[0]
  }' >"${payload}"

dashboard_api="${grafana_url}/apis/dashboard.grafana.app/v1/namespaces/default/dashboards"
dashboard_status="$(curl "${curl_args[@]}" --output /dev/null --write-out '%{http_code}' \
  "${dashboard_api}/${dashboard_uid}" || true)"
if [[ "${dashboard_status}" == "200" ]]; then
  method="PUT"
  endpoint="${dashboard_api}/${dashboard_uid}"
elif [[ "${dashboard_status}" == "404" ]]; then
  method="POST"
  endpoint="${dashboard_api}"
else
  echo "Could not inspect dashboard (HTTP ${dashboard_status}). Configure Grafana credentials." >&2
  exit 1
fi

response="$(curl "${curl_args[@]}" \
  --header 'Content-Type: application/json' \
  --request "${method}" \
  --data-binary "@${payload}" \
  "${endpoint}")"

uploaded_uid="$(jq -r '.metadata.name // empty' <<<"${response}")"
if [[ "${uploaded_uid}" != "${dashboard_uid}" ]]; then
  echo "Grafana did not confirm the uploaded dashboard: ${response}" >&2
  exit 1
fi

echo "Uploaded dashboard to ${grafana_url}/d/${dashboard_uid}"
