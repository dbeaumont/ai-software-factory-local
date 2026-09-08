#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
ui_port=${TEMPORAL_UI_PORT:-8233}
repository_url=${TEMPORAL_TEST_REPOSITORY_URL:-http://gitea:3000/${GITEA_ADMIN_USER:-aiadmin}/customer-api.git}
requirement=${TEMPORAL_TEST_REQUIREMENT:-Temporal UI verification: document the HTTP 404 expectation in the customer not-found test.}

response_file=$(mktemp)
ui_file=$(mktemp)
trap 'rm -f "$response_file" "$ui_file"' EXIT

for service in evidence-mcp orchestrator temporal-ui; do
  ready=false
  for _ in $(seq 1 60); do
    state=$("${compose[@]}" ps --format json "$service" | jq -sr '.[0].State // empty')
    health=$("${compose[@]}" ps --format json "$service" | jq -sr '.[0].Health // empty')
    if [ "$state" = "running" ] && { [ -z "$health" ] || [ "$health" = "healthy" ]; }; then
      ready=true
      break
    fi
    sleep 1
  done
  [ "$ready" = true ] || { echo "Compose service is not ready: $service" >&2; exit 1; }
done

payload=$(jq -cn --arg repositoryUrl "$repository_url" --arg requirement "$requirement" \
  '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement,routingFacts:{qualification:"QUALIFIED",risk:"R1",modules:1,domains:1,estimatedFiles:2,independentCodeScopes:1,impacts:[],materialDecisionOpen:false,inputsComplete:true,contradictory:false,budgetAvailable:true}}')
curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
  -H 'Content-Type: application/json' --data "$payload" >"$response_file"

task_id=$(jq -er '.id' "$response_file")
attempt_id=$(jq -er '.workflowAttemptId' "$response_file")
run_id=$(jq -er '.workflowRunId' "$response_file")
workflow_id="ai-factory/${task_id}/${attempt_id}"

found=false
for _ in $(seq 1 30); do
  curl -fsS --get "http://127.0.0.1:${ui_port}/api/v1/namespaces/${namespace}/workflows" \
    --data-urlencode "query=AiFactoryTaskId=\"${task_id}\"" >"$ui_file"
  if jq -e --arg workflow "$workflow_id" --arg run "$run_id" \
      '.executions[] | select(.execution.workflowId == $workflow and .execution.runId == $run)' \
      "$ui_file" >/dev/null; then
    found=true
    break
  fi
  sleep 1
done
[ "$found" = true ] || { echo "Ticket $task_id is absent from Temporal UI." >&2; exit 1; }

temporal_cli=("${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-namespace)
"${temporal_cli[@]}" workflow describe --address temporal:7233 --namespace "$namespace" \
  --workflow-id "$workflow_id" --run-id "$run_id" >/dev/null

status=$(jq -r --arg workflow "$workflow_id" --arg run "$run_id" \
  '.executions[] | select(.execution.workflowId == $workflow and .execution.runId == $run) | .status' \
  "$ui_file")
echo "Temporal UI ticket verified: task=$task_id workflow=$workflow_id run=$run_id status=$status"
