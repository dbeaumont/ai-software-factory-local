#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

base_url=${SIGNOZ_BASE_URL:-http://127.0.0.1:${SIGNOZ_PORT:-3301}}
email=${SIGNOZ_ROOT_EMAIL:-admin@ai-factory.local}
: "${SIGNOZ_ROOT_PASSWORD:?SIGNOZ_ROOT_PASSWORD must be initialized by make init}"

context=$(curl -fsS --get "$base_url/api/v2/sessions/context" --data-urlencode "email=$email" --data-urlencode "ref=$base_url")
org_id=$(printf '%s' "$context" | jq -er '.data.orgs[0].id')
payload=$(jq -nc --arg email "$email" --arg password "$SIGNOZ_ROOT_PASSWORD" --arg orgId "$org_id" '{email:$email,password:$password,orgId:$orgId}')
session=$(curl -fsS -X POST "$base_url/api/v2/sessions/email_password" -H 'Content-Type: application/json' --data "$payload")
token=$(printf '%s' "$session" | jq -er '.data.accessToken')
trap 'curl -fsS -X DELETE "$base_url/api/v2/sessions" -H "Authorization: Bearer $token" >/dev/null || true' EXIT
auth=(-H "Authorization: Bearer $token")

metrics=$(curl -fsS "$base_url/api/v2/metrics?limit=5000" "${auth[@]}")
metric_count=$(printf '%s' "$metrics" | jq -er '.data.metrics // .metrics | length')
[ "$metric_count" -gt 0 ] || { echo "No metric was ingested by SigNoz" >&2; exit 1; }

dashboards=$(curl -fsS "$base_url/api/v2/dashboards?limit=100" "${auth[@]}")
dashboard_count=$(printf '%s' "$dashboards" | jq '[.data.dashboards[] | select(.tags[]? | .key == "project" and .value == "ai-software-factory")] | length')
[ "$dashboard_count" -eq 7 ] || { echo "Expected 7 managed dashboards, got $dashboard_count" >&2; exit 1; }

rules=$(curl -fsS "$base_url/api/v2/rules" "${auth[@]}")
rule_count=$(printf '%s' "$rules" | jq '[.data[] | select(.labels.managed_by == "ai-software-factory")] | length')
[ "$rule_count" -eq 9 ] || { echo "Expected 9 managed rules, got $rule_count" >&2; exit 1; }

metrics_retention=$(curl -fsS --get "$base_url/api/v1/settings/ttl" "${auth[@]}" --data-urlencode 'type=metrics' | jq -er '.metrics_ttl_duration_hrs')
traces_retention=$(curl -fsS --get "$base_url/api/v1/settings/ttl" "${auth[@]}" --data-urlencode 'type=traces' | jq -er '.traces_ttl_duration_hrs')
logs_retention=$(curl -fsS "$base_url/api/v2/settings/ttl" "${auth[@]}" | jq -er '.default_ttl_days')
[ "$metrics_retention" -gt 0 ] && [ "$metrics_retention" -le 720 ] || { echo "Metrics retention must be bounded to at most 30 days" >&2; exit 1; }
[ "$traces_retention" -gt 0 ] && [ "$traces_retention" -le 360 ] || { echo "Traces retention must be bounded to at most 15 days" >&2; exit 1; }
[ "$logs_retention" -gt 0 ] && [ "$logs_retention" -le 15 ] || { echo "Logs retention must be bounded to at most 15 days" >&2; exit 1; }

printf 'SigNoz telemetry ready: metrics=%s dashboards=%s alerts=%s retention=%sh/%sh/%sd\n' \
  "$metric_count" "$dashboard_count" "$rule_count" "$metrics_retention" "$traces_retention" "$logs_retention"
printf '%s' "$metrics" | jq -r '(.data.metrics // .metrics)[] | .metricName' \
  | grep -E '^(ai[._]|http[._]|jvm[._]|otelcol[._]|service_)' | sort -u | head -40
