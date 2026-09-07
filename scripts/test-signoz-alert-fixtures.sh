#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
source scripts/load-dotenv.sh
load_dotenv .env

now=$(date +%s)
old="$((now - 120))000000000"
current="${now}000000000"

payload=$(jq -nc --arg old "$old" --arg current "$current" '
  def a($key;$value): {key:$key,value:{stringValue:$value}};
  def p($time;$value;$attrs): {timeUnixNano:$time,asDouble:$value,attributes:$attrs};
  def counter($name;$series): {name:$name,sum:{aggregationTemporality:2,isMonotonic:true,dataPoints:$series}};
  def gauge($name;$value;$attrs): {name:$name,gauge:{dataPoints:[p($current;$value;$attrs)]}};
  {resourceMetrics:[{
    resource:{attributes:[a("service.name";"otel-alert-fixture"),a("service.namespace";"ai-factory"),a("deployment.environment.name";"ai-factory-local")]},
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
      gauge("ai_factory_sandbox_jobs_queued";21;[]),
      gauge("ai_task_queue_saturation_ratio";0.95;[]),
      counter("ai_factory_sandbox_heartbeat_invalid";[p($old;0;[]),p($current;1;[])]),
      counter("ai_factory_sandbox_jobs_failed";[p($old;0;[]),p($current;6;[])]),
      counter("ai_factory_sandbox_maintenance_failures";[p($old;0;[]),p($current;1;[])]),
      counter("ai_evidence_altered";[p($old;0;[]),p($current;1;[])]),
      gauge("ai_temporal_task_queue_pollers";0;[a("perimeter";"workflow"),a("task_type";"workflow")]),
      gauge("ai_temporal_task_queue_pollers";0;[a("perimeter";"llm"),a("task_type";"activity")]),
      gauge("ai_temporal_task_queue_backlog";21;[a("perimeter";"llm"),a("task_type";"activity")]),
      counter("ai_temporal_workflow_nondeterministic";[p($old;0;[]),p($current;1;[])]),
      gauge("ai_temporal_projection_lag_seconds";61;[]),
      counter("ai_temporal_timeouts";[p($old;0;[]),p($current;1;[])]),
      counter("ai_temporal_continue_as_new_requested";[p($old;0;[]),p($current;1;[])]),
      counter("temporal_workflow_continue_as_new";[p($old;0;[]),p($current;0;[])])
      ,gauge("temporal_num_pollers";0;[a("task_queue";"a2a-agent-developer-v1")])
      ,gauge("ai.factory.a2a.server.ready";0;[a("agent_role";"developer")])
      ,counter("ai.factory.a2a.client.card.validations";[p($old;0;[a("result";"rejected")]),p($current;1;[a("result";"rejected")])])
      ,counter("ai.factory.a2a.server.transitions";[p($old;0;[a("task_state";"failed")]),p($current;1;[a("task_state";"failed")])])
      ,gauge("ai.factory.a2a.server.backlog";21;[a("agent_role";"developer")])
      ,gauge("ai.factory.a2a.server.oldest.active.age";301;[a("agent_role";"developer")])
      ,gauge("ai.factory.a2a.client.notification.age.max";60001;[a("agent_role";"developer")])
      ,counter("ai.factory.a2a.server.idempotency.collisions";[p($old;0;[]),p($current;1;[])])
      ,counter("ai.factory.a2a.client.divergences";[p($old;0;[]),p($current;1;[])])
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
expected=$(jq '[.[] | select(.labels.component != "observability")] | length' \
  infrastructure/observability/signoz/rules/ai-factory.json)
validated=0
for attempt in {1..20}; do
  validated=0
  while IFS= read -r query; do
    request=$(jq -nc --arg query "$query" --argjson start "$start" --argjson end "$end" \
      '{schemaVersion:"v1",start:$start,end:$end,requestType:"time_series",compositeQuery:{queries:[{type:"promql",spec:{name:"A",query:$query,step:30}}]}}')
    response=$(curl -fsS -X POST "$base_url/api/v5/query_range" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$request")
    if printf '%s' "$response" | jq -e \
      '.status == "success" and ([.data.data.results[]?.aggregations[]?.series[]?] | length > 0)' >/dev/null; then
      validated=$((validated + 1))
    fi
  done < <(jq -r '.[] | select(.labels.component != "observability") | .condition.compositeQuery.queries[0].spec.query' infrastructure/observability/signoz/rules/ai-factory.json)
  [ "$validated" -eq "$expected" ] && break
  sleep 1
done

[ "$validated" -eq "$expected" ] || {
  echo "Only $validated/$expected alert fixtures produced a positive query result" >&2
  printf '%s\n' "$response" | jq . >&2
  exit 1
}

# Move the same cumulative streams past the longest PromQL window with stable counters and healthy gauges.
recovery_one="$((now + 1000))000000000"
recovery_two="$((now + 1120))000000000"
recovery_payload=$(jq -nc --arg one "$recovery_one" --arg two "$recovery_two" '
  def a($key;$value): {key:$key,value:{stringValue:$value}};
  def p($time;$value;$attrs): {timeUnixNano:$time,asDouble:$value,attributes:$attrs};
  def counter($name;$value;$attrs): {name:$name,sum:{aggregationTemporality:2,isMonotonic:true,dataPoints:[p($one;$value;$attrs),p($two;$value;$attrs)]}};
  def gauge($name;$value;$attrs): {name:$name,gauge:{dataPoints:[p($one;$value;$attrs),p($two;$value;$attrs)]}};
  {resourceMetrics:[{
    resource:{attributes:[a("service.name";"otel-alert-fixture"),a("service.namespace";"ai-factory"),a("deployment.environment.name";"ai-factory-local")]},
    scopeMetrics:[{scope:{name:"ai-factory-alert-fixture",version:"1"},metrics:[
      counter("ai_agent_failures";1;[a("reason";"repeated_call"),a("stop_condition";"LOOP_DETECTED")]),
      counter("ai_agent_failures";1;[a("reason";"budget"),a("stop_condition";"BUDGET_EXHAUSTED")]),
      counter("ai_agent_failures";1;[a("reason";"contract"),a("stop_condition";"CONTRACT_ERROR")]),
      counter("ai_agent_cost_micros";6000001;[]),
      gauge("ai_factory_sandbox_jobs_queued";0;[]),
      gauge("ai_task_queue_saturation_ratio";0.1;[]),
      counter("ai_factory_sandbox_heartbeat_invalid";1;[]),
      counter("ai_factory_sandbox_jobs_failed";6;[]),
      counter("ai_factory_sandbox_maintenance_failures";1;[]),
      counter("ai_evidence_altered";1;[]),
      gauge("ai_temporal_task_queue_pollers";1;[a("perimeter";"workflow"),a("task_type";"workflow")]),
      gauge("ai_temporal_task_queue_pollers";1;[a("perimeter";"llm"),a("task_type";"activity")]),
      gauge("ai_temporal_task_queue_backlog";0;[a("perimeter";"llm"),a("task_type";"activity")]),
      counter("ai_temporal_workflow_nondeterministic";1;[]),
      gauge("ai_temporal_projection_lag_seconds";0;[]),
      counter("ai_temporal_timeouts";1;[]),
      counter("ai_temporal_continue_as_new_requested";1;[]),
      counter("temporal_workflow_continue_as_new";1;[])
      ,gauge("temporal_num_pollers";1;[a("task_queue";"a2a-agent-developer-v1")])
      ,gauge("ai.factory.a2a.server.ready";1;[a("agent_role";"developer")])
      ,counter("ai.factory.a2a.client.card.validations";1;[a("result";"rejected")])
      ,counter("ai.factory.a2a.server.transitions";1;[a("task_state";"failed")])
      ,gauge("ai.factory.a2a.server.backlog";0;[a("agent_role";"developer")])
      ,gauge("ai.factory.a2a.server.oldest.active.age";0;[a("agent_role";"developer")])
      ,gauge("ai.factory.a2a.client.notification.age.max";0;[a("agent_role";"developer")])
      ,counter("ai.factory.a2a.server.idempotency.collisions";1;[])
      ,counter("ai.factory.a2a.client.divergences";1;[])
    ]}]
  }]}' )

"${compose[@]}" exec -T orchestrator curl -fsS \
  -X POST http://otel-collector:4318/v1/metrics \
  -H 'Content-Type: application/json' --data-binary "$recovery_payload" >/dev/null

recovery_start=$(((now + 1000) * 1000))
recovery_end=$(((now + 1150) * 1000))
recovered=0
for attempt in {1..20}; do
  recovered=0
  unrecovered=()
  while IFS=$'\t' read -r alert_name query; do
    request=$(jq -nc --arg query "$query" --argjson start "$recovery_start" --argjson end "$recovery_end" \
      '{schemaVersion:"v1",start:$start,end:$end,requestType:"time_series",compositeQuery:{queries:[{type:"promql",spec:{name:"A",query:$query,step:30}}]}}')
    response=$(curl -fsS -X POST "$base_url/api/v5/query_range" \
      -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$request")
    if printf '%s' "$response" | jq -e \
      '.status == "success" and ([.data.data.results[]?.aggregations[]?.series[]?] | length == 0)' >/dev/null; then
      recovered=$((recovered + 1))
    else
      unrecovered+=("$alert_name")
    fi
  done < <(jq -r '.[] | select(.labels.component != "observability")
    | [.alert, .condition.compositeQuery.queries[0].spec.query] | @tsv' infrastructure/observability/signoz/rules/ai-factory.json)
  [ "$recovered" -eq "$expected" ] && break
  sleep 1
done

[ "$recovered" -eq "$expected" ] || {
  echo "Only $recovered/$expected alert fixtures returned to a healthy query state" >&2
  printf 'Still firing: %s\n' "${unrecovered[*]}" >&2
  printf '%s\n' "$response" | jq . >&2
  exit 1
}
echo "Validated $expected/$expected SigNoz alert rules firing and automatic recovery with deterministic OTLP metrics."
