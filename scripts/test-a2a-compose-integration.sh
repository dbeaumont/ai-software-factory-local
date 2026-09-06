#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root}"

export A2A_INTEGRATION_PROJECT="ai-factory-a2a-it-$$"
compose=(docker compose -p "${A2A_INTEGRATION_PROJECT}" --env-file .env
  -f infrastructure/compose.yaml -f infrastructure/a2a/compose-integration.yaml
  --profile a2a-developer)
evidence="${root}/docs/evidence/a2a/A2A-165-COMPOSE-INTEGRATION.log"
message_id="compose-initial-$$"
continuation_id="compose-continuation-$$"
cancel_message_id="compose-cancel-$$"
digest="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

cleanup() {
  "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_healthy() {
  local service="$1" container health deadline
  deadline=$((SECONDS + 180))
  while true; do
    container="$("${compose[@]}" ps -q "${service}")"
    health="$(test -n "${container}" && docker inspect --format '{{.State.Health.Status}}' "${container}" || echo missing)"
    [[ "${health}" == healthy ]] && return
    [[ "${health}" != unhealthy ]] || {
      "${compose[@]}" logs "${service}" >&2
      echo "${service} became unhealthy" >&2
      exit 1
    }
    (( SECONDS < deadline )) || {
      "${compose[@]}" logs "${service}" >&2
      echo "Timed out waiting for ${service}" >&2
      exit 1
    }
    sleep 2
  done
}

rpc() {
  "${compose[@]}" exec -T a2a-developer curl --fail --silent \
    -H 'A2A-Version: 1.0' -H 'Content-Type: application/json' \
    --data-binary "$1" http://127.0.0.1:8090/a2a
}

json_value() {
  local expression="$1"
  python3 -c "import json,sys; print(${expression})"
}

send_request() {
  local rpc_id="$1" current_message_id="$2" input_digest="$3" task_fields="${4:-}"
  printf '%s' "{\"jsonrpc\":\"2.0\",\"id\":\"${rpc_id}\",\"method\":\"message/send\",\"params\":{\"configuration\":{\"blocking\":false},\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"${current_message_id}\"${task_fields},\"parts\":[{\"kind\":\"data\",\"data\":{\"schema_version\":\"1\",\"target_role\":\"developer\",\"skill_id\":\"developer.code-task-v1\",\"input_references\":[{\"digest\":\"${input_digest}\"}]}}],\"metadata\":{\"https://ai-factory.local/extensions/execution-context/v1\":{\"schemaVersion\":\"1\",\"taskId\":\"external-task-${rpc_id}\",\"attemptId\":\"attempt-1\",\"workflowId\":\"workflow-${rpc_id}\",\"workflowRunId\":\"run-1\",\"repositoryId\":\"repository-1\",\"sourceCommit\":\"0123456789abcdef0123456789abcdef01234567\",\"delegationId\":\"delegation-compose\",\"agentRole\":\"developer\",\"inputDigests\":[\"${input_digest}\"]},\"https://ai-factory.local/extensions/w3c-trace-context/v1\":{\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\",\"baggage\":\"task.id=external-task-${rpc_id}\"}}}}}"
}

mkdir -p "$(dirname "${evidence}")"
"${compose[@]}" up --build --detach a2a-task-db a2a-developer
wait_healthy a2a-task-db
wait_healthy a2a-developer

card="$("${compose[@]}" exec -T a2a-developer curl --fail --silent \
  http://127.0.0.1:8090/.well-known/agent-card.json)"
printf '%s' "${card}" | python3 -c 'import json,sys; card=json.load(sys.stdin); assert card["protocolVersion"] == "1.0"; assert card["url"].startswith("http://a2a-developer:")'

initial_body="$(send_request initial "${message_id}" "${digest}")"
initial="$(rpc "${initial_body}")"
task_id="$(printf '%s' "${initial}" | json_value 'json.load(sys.stdin)["result"]["id"]')"
context_id="$(printf '%s' "${initial}" | json_value 'json.load(sys.stdin)["result"]["contextId"]')"
initial_state="$(printf '%s' "${initial}" | json_value 'json.load(sys.stdin)["result"]["status"]["state"]')"
[[ "${initial_state}" == TASK_STATE_SUBMITTED ]]

replayed="$(rpc "${initial_body}")"
replayed_id="$(printf '%s' "${replayed}" | json_value 'json.load(sys.stdin)["result"]["id"]')"
[[ "${replayed_id}" == "${task_id}" ]]

to_input_required="UPDATE a2a_agent_task SET task_state='INPUT_REQUIRED', version=1 WHERE task_id='${task_id}' AND version=0; INSERT INTO a2a_agent_task_history (task_id,message_id,event_type,occurred_at,task_version) VALUES ('${task_id}','${message_id}','TASK_INPUT_REQUIRED',CURRENT_TIMESTAMP,1);"
"${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a \
  -v ON_ERROR_STOP=1 -c "${to_input_required}" >/dev/null

continuation_digest="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
continuation_fields=",\"taskId\":\"${task_id}\",\"contextId\":\"${context_id}\""
continuation_body="$(send_request continuation "${continuation_id}" "${continuation_digest}" "${continuation_fields}")"
continued="$(rpc "${continuation_body}")"
continued_state="$(printf '%s' "${continued}" | json_value 'json.load(sys.stdin)["result"]["status"]["state"]')"
[[ "${continued_state}" == TASK_STATE_SUBMITTED ]]

"${compose[@]}" restart a2a-developer >/dev/null
wait_healthy a2a-developer
get_request="{\"jsonrpc\":\"2.0\",\"id\":\"get-after-restart\",\"method\":\"tasks/get\",\"params\":{\"id\":\"${task_id}\",\"historyLength\":50}}"
after_restart="$(rpc "${get_request}")"
working_state="$(printf '%s' "${after_restart}" | json_value 'json.load(sys.stdin)["result"]["status"]["state"]')"
[[ "${working_state}" == TASK_STATE_WORKING ]]

artifact_id="artifact-compose-$$"
complete_sql="INSERT INTO a2a_agent_task_artifact (artifact_id,task_id,tenant_id,acl_subject,artifact_digest,artifact_json,version) VALUES ('${artifact_id}','${task_id}','local-compose','ai-factory-compose-qualification','${digest}','{\"artifactId\":\"${artifact_id}\",\"kind\":\"compose-result\"}',0); UPDATE a2a_agent_task SET task_state='COMPLETED', version=3 WHERE task_id='${task_id}' AND task_state='WORKING' AND version=2; INSERT INTO a2a_agent_task_history (task_id,message_id,event_type,occurred_at,task_version) VALUES ('${task_id}','${continuation_id}','TASK_COMPLETED',CURRENT_TIMESTAMP,3); INSERT INTO a2a_agent_notification_outbox (notification_id,task_id,context_id,agent_role,task_sequence,task_state,occurred_at) VALUES ('notify-${task_id}-3','${task_id}','${context_id}','developer',3,'COMPLETED',CURRENT_TIMESTAMP);"
"${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a \
  -v ON_ERROR_STOP=1 -c "${complete_sql}" >/dev/null

reconciled="$(rpc "${get_request}")"
completed_state="$(printf '%s' "${reconciled}" | json_value 'json.load(sys.stdin)["result"]["status"]["state"]')"
artifact_result="$(printf '%s' "${reconciled}" | json_value 'json.load(sys.stdin)["result"]["artifacts"][0]["artifactId"]')"
history_count="$(printf '%s' "${reconciled}" | json_value 'len(json.load(sys.stdin)["result"]["history"])')"
pending_callbacks="$("${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a -At \
  -c "SELECT count(*) FROM a2a_agent_notification_outbox WHERE task_id='${task_id}' AND acknowledged_at IS NULL")"
[[ "${completed_state}" == TASK_STATE_COMPLETED && "${artifact_result}" == "${artifact_id}" ]]
[[ "${history_count}" == 4 && "${pending_callbacks}" == 1 ]]

cancel_body="$(send_request cancel "${cancel_message_id}" "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc")"
cancel_submission="$(rpc "${cancel_body}")"
cancel_task_id="$(printf '%s' "${cancel_submission}" | json_value 'json.load(sys.stdin)["result"]["id"]')"
cancel_request="{\"jsonrpc\":\"2.0\",\"id\":\"cancel-task\",\"method\":\"tasks/cancel\",\"params\":{\"id\":\"${cancel_task_id}\"}}"
canceled="$(rpc "${cancel_request}")"
canceled_state="$(printf '%s' "${canceled}" | json_value 'json.load(sys.stdin)["result"]["status"]["state"]')"
[[ "${canceled_state}" == TASK_STATE_CANCELED ]]

{
  echo "A2A-165 Compose integration qualification"
  echo "agent_card=valid protocol=1.0"
  echo "send=idempotent task=${task_id}"
  echo "continuation=accepted state=${working_state}"
  echo "restart=durable"
  echo "result=${completed_state} artifact=${artifact_result} history=${history_count}"
  echo "lost_callback=pending:${pending_callbacks} reconciliation=polling-success"
  echo "cancellation=${canceled_state}"
} | tee "${evidence}"
