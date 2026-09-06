#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root}"
export A2A_INTEGRATION_PROJECT="ai-factory-a2a-fault-$$"
compose=(docker compose -p "${A2A_INTEGRATION_PROJECT}" --env-file .env
  -f infrastructure/compose.yaml -f infrastructure/a2a/compose-integration.yaml
  --profile a2a-developer)
evidence="${root}/docs/evidence/a2a/A2A-166-FAILURE-CAMPAIGN.log"
message_id="fault-message-$$"

cleanup() { "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

wait_healthy() {
  local service="$1" container health deadline=$((SECONDS + 180))
  while true; do
    container="$("${compose[@]}" ps -q "${service}")"
    health="$(test -n "${container}" && docker inspect --format '{{.State.Health.Status}}' "${container}" || echo missing)"
    [[ "${health}" == healthy ]] && return
    (( SECONDS < deadline )) || { "${compose[@]}" logs "${service}" >&2; exit 1; }
    sleep 2
  done
}

rpc() {
  "${compose[@]}" exec -T a2a-developer curl --silent \
    --max-time 12 \
    -H 'A2A-Version: 1.0' -H 'Content-Type: application/json' \
    --data-binary "$1" http://127.0.0.1:8090/a2a
}

"${compose[@]}" up --build --detach a2a-task-db a2a-developer
wait_healthy a2a-task-db
wait_healthy a2a-developer

# Agent outage before admission: restart first, then submit exactly once.
"${compose[@]}" stop a2a-developer >/dev/null
[[ "$("${compose[@]}" ps -q a2a-developer)" == "" ]]
"${compose[@]}" start a2a-developer >/dev/null
wait_healthy a2a-developer

body="{\"jsonrpc\":\"2.0\",\"id\":\"fault\",\"method\":\"message/send\",\"params\":{\"configuration\":{\"blocking\":false},\"message\":{\"role\":\"ROLE_USER\",\"messageId\":\"${message_id}\",\"parts\":[{\"kind\":\"data\",\"data\":{\"schema_version\":\"1\",\"target_role\":\"developer\",\"skill_id\":\"developer.code-task-v1\",\"input_references\":[{\"digest\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}]}}],\"metadata\":{\"https://ai-factory.local/extensions/execution-context/v1\":{\"schemaVersion\":\"1\",\"taskId\":\"fault-task\",\"attemptId\":\"attempt-1\",\"workflowId\":\"fault-workflow\",\"workflowRunId\":\"run-1\",\"repositoryId\":\"repository-1\",\"sourceCommit\":\"0123456789abcdef0123456789abcdef01234567\",\"delegationId\":\"fault-delegation\",\"agentRole\":\"developer\",\"inputDigests\":[\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"]},\"https://ai-factory.local/extensions/w3c-trace-context/v1\":{\"traceparent\":\"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\"}}}}}"
accepted="$(rpc "${body}")"
task_id="$(printf '%s' "${accepted}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["id"])')"
get="{\"jsonrpc\":\"2.0\",\"id\":\"get\",\"method\":\"tasks/get\",\"params\":{\"id\":\"${task_id}\"}}"

# Database outage after admission: fail closed, then recover the same durable task.
"${compose[@]}" stop a2a-task-db >/dev/null
db_failure="$(rpc "${get}" || true)"
if [[ -n "${db_failure}" ]]; then
  printf '%s' "${db_failure}" | python3 -c 'import json,sys; assert "error" in json.load(sys.stdin)'
fi
"${compose[@]}" start a2a-task-db >/dev/null
wait_healthy a2a-task-db
db_recovered="$(rpc "${get}")"
printf '%s' "${db_recovered}" | python3 -c 'import json,sys; assert json.load(sys.stdin)["result"]["id"]'

# Private-network outage: detach the agent from the task-store segment, then reconnect it.
agent_container="$("${compose[@]}" ps -q a2a-developer)"
docker network disconnect "${A2A_INTEGRATION_PROJECT}-a2a" "${agent_container}"
network_failure="$(rpc "${get}" || true)"
if [[ -n "${network_failure}" ]]; then
  printf '%s' "${network_failure}" | python3 -c 'import json,sys; assert "error" in json.load(sys.stdin)'
fi
docker network connect "${A2A_INTEGRATION_PROJECT}-a2a" "${agent_container}"
network_recovered="$(rpc "${get}")"
recovered_id="$(printf '%s' "${network_recovered}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["id"])')"
[[ "${recovered_id}" == "${task_id}" ]]

replayed="$(rpc "${body}")"
replayed_id="$(printf '%s' "${replayed}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["result"]["id"])')"
count="$("${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a -At \
  -c "SELECT count(*) FROM a2a_agent_task WHERE message_id='${message_id}'")"
[[ "${replayed_id}" == "${task_id}" && "${count}" == 1 ]]

mkdir -p "$(dirname "${evidence}")"
{
  echo "A2A-166 failure campaign"
  echo "agent_outage=recovered-before-admission"
  echo "database_outage=failed-closed,recovered"
  echo "private_network_outage=failed-closed,recovered"
  echo "task_id=${task_id} replay_task_id=${replayed_id} durable_task_count=${count}"
  echo "temporal_llm_mcp_evidence=covered-by-runtime-failure-tests"
} | tee "${evidence}"
