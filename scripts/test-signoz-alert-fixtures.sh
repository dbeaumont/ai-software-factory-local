#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
[ -f .env ] && set -a && source .env && set +a

now=$(date +%s)
old="$((now - 120))000000000"
current="${now}000000000"

payload=$(jq -nc --arg old "$old" --arg current "$current" '
  def a($key;$value): {key:$key,value:{stringValue:$value}};
  def p($time;$value;$attrs): {timeUnixNano:$time,asDouble:$value,attributes:$attrs};
  def counter($name;$series): {name:$name,sum:{aggregationTemporality:2,isMonotonic:true,dataPoints:$series}};
  def gauge($name;$value): {name:$name,gauge:{dataPoints:[p($current;$value;[])]}};
  {resourceMetrics:[{
    resource:{attributes:[a("service.name";"otel-alert-fixture"),a("service.namespace";"ai-software-factory"),a("deployment.environment.name";"ai-factory-local")]},
    scopeMetrics:[{scope:{name:"ai-factory-alert-fixture",version:"1"},metrics:[
      counter("ai_agent_failures";[
        p($old;0;[a("reason";"repeated_call"),a("stop_condition";"LOOP_DETECTED")]),
        p($current;1;[a("reason";"repeated_call"),a("stop_condition";"LOOP_DETECTED")]),
        p($old;0;[a("reason";"budget"),a("stop_condition";"BUDGET_EXHAUSTED")]),
        p($current;1;[a("reason";"budget"),a("stop_condition";"BUDGET_EXHAUSTED")]),
        p($old;0;[a("reason";"contract"),a("stop_condition";"CONTRACT_ERROR")]),
        p($current;1;[a("reason";"contract"),a("stop_condition";"CONTRACT_ERROR")])
      ]),
      counter("ai_agent_cost_micros";[p($old;0;[]),p($current;6000001;[])]),
      gauge("ai_factory_sandbox_jobs_queued";21),
      gauge("ai_task_queue_saturation_ratio";0.95),
      counter("ai_factory_sandbox_heartbeat_invalid";[p($old;0;[]),p($current;1;[])]),
      counter("ai_factory_sandbox_jobs_failed";[p($old;0;[]),p($current;6;[])]),
      counter("ai_factory_sandbox_maintenance_failures";[p($old;0;[]),p($current;1;[])]),
      counter("ai_evidence_altered";[p($old;0;[]),p($current;1;[])])
    ]}]
  }]}' )

"${compose[@]}" exec -T orchestrator curl -fsS \
  -X POST http://otel-collector:4318/v1/metrics \
  -H 'Content-Type: application/json' --data-binary "$payload" >/dev/null

base_url=${SIGNOZ_BASE_URL:-http://127.0.0.1:${SIGNOZ_PORT:-3301}}
email=${SIGNOZ_ROOT_EMAIL:-admin@ai-factory.local}
: "${SIGNOZ_ROOT_PASSWORD:?SIGNOZ_ROOT_PASSWORD must be initialized by make init}"
context=$(curl -fsS --get "$base_url/api/v2/sessions/context" --data-urlencode "email=$email" --data-urlencode "ref=$base_url")
org_id=$(printf '%s' "$context" | jq -er '.data.orgs[0].id')
login=$(jq -nc --arg email "$email" --arg password "$SIGNOZ_ROOT_PASSWORD" --arg orgId "$org_id" '{email:$email,password:$password,orgId:$orgId}')
session=$(curl -fsS -X POST "$base_url/api/v2/sessions/email_password" -H 'Content-Type: application/json' --data "$login")
token=$(printf '%s' "$session" | jq -er '.data.accessToken')
trap 'curl -fsS -X DELETE "$base_url/api/v2/sessions" -H "Authorization: Bearer $token" >/dev/null || true' EXIT

start=$(((now - 600) * 1000))
end=$(((now + 30) * 1000))
validated=0
for attempt in {1..20}; do
  validated=0
  while IFS= read -r query; do
    request=$(jq -nc --arg query "$query" --argjson start "$start" --argjson end "$end" \
      '{schemaVersion:"v1",start:$start,end:$end,requestType:"time_series",compositeQuery:{queries:[{type:"promql",spec:{name:"A",query:$query,step:30}}]}}')
    response=$(curl -fsS -X POST "$base_url/api/v5/query_range" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$request")
    if printf '%s' "$response" | jq -e \
      '.status == "success" and ([.data.data.results[].aggregations[].series[]] | length > 0)' >/dev/null; then
      validated=$((validated + 1))
    fi
  done < <(jq -r '.[] | select(.labels.component != "observability") | .condition.compositeQuery.queries[0].spec.query' infrastructure/observability/signoz/rules/ai-factory.json)
  [ "$validated" -eq 9 ] && break
  sleep 1
done

[ "$validated" -eq 9 ] || {
  echo "Only $validated/9 alert fixtures produced a positive query result" >&2
  printf '%s\n' "$response" | jq . >&2
  exit 1
}
echo "Validated 9/9 SigNoz alert rules with deterministic OTLP metrics."
