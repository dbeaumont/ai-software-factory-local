#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

base_url=${SIGNOZ_BASE_URL:-http://127.0.0.1:${SIGNOZ_PORT:-3301}}
email=${SIGNOZ_ROOT_EMAIL:-admin@ai-factory.local}
: "${SIGNOZ_ROOT_PASSWORD:?SIGNOZ_ROOT_PASSWORD must be initialized by make init}"

context=$(curl -fsS --get "$base_url/api/v2/sessions/context" --data-urlencode "email=$email" --data-urlencode "ref=$base_url")
org_id=$(printf '%s' "$context" | jq -er '.data.orgs[0].id')
login=$(jq -nc --arg email "$email" --arg password "$SIGNOZ_ROOT_PASSWORD" --arg orgId "$org_id" '{email:$email,password:$password,orgId:$orgId}')
session=$(curl -fsS -X POST "$base_url/api/v2/sessions/email_password" -H 'Content-Type: application/json' --data "$login")
token=$(printf '%s' "$session" | jq -er '.data.accessToken')
trap 'curl -fsS -X DELETE "$base_url/api/v2/sessions" -H "Authorization: Bearer $token" >/dev/null || true' EXIT

now=$(date +%s)
start=$(((now - 3600) * 1000))
end=$((now * 1000))
query_file=$(mktemp)
trap 'rm -f "$query_file"; curl -fsS -X DELETE "$base_url/api/v2/sessions" -H "Authorization: Bearer $token" >/dev/null || true' EXIT

jq -r '.spec.panels[].spec.queries[].spec.plugin.spec.queries[].spec.query' \
  infrastructure/observability/signoz/dashboards/*.json >"$query_file"
jq -r '.[].condition.compositeQuery.queries[].spec.query' \
  infrastructure/observability/signoz/rules/*.json >>"$query_file"

validated=0
while IFS= read -r query; do
  payload=$(jq -nc --arg query "$query" --argjson start "$start" --argjson end "$end" \
    '{schemaVersion:"v1",start:$start,end:$end,requestType:"time_series",compositeQuery:{queries:[{type:"promql",spec:{name:"A",query:$query,step:60}}]}}')
  response=$(curl -fsS -X POST "$base_url/api/v5/query_range" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$payload")
  if ! printf '%s' "$response" | jq -e '.status == "success" and (.error == null)' >/dev/null; then
    printf 'Invalid SigNoz query: %s\n%s\n' "$query" "$response" >&2
    exit 1
  fi
  validated=$((validated + 1))
done < <(sort -u "$query_file")

printf 'Validated %d unique SigNoz dashboard and alert queries.\n' "$validated"
