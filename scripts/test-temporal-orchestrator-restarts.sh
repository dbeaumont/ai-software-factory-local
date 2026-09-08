#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
repository_url=${TEMPORAL_TEST_REPOSITORY_URL:-http://gitea:3000/${GITEA_ADMIN_USER:-aiadmin}/customer-api.git}
requirement=${TEMPORAL_RESTART_TEST_REQUIREMENT:-Temporal restart verification: add one concise comment to the customer not-found test without changing behavior.}
phases=${TEMPORAL_RESTART_PHASES:-PLANNING GENERATING_PATCH APPLYING_PATCH TESTING QUALITY_SCANNING SECURITY_SCANNING REVIEWING WAITING_APPROVAL}
phase_timeout=${TEMPORAL_RESTART_PHASE_TIMEOUT_SECONDS:-1200}
phase_attempts=${TEMPORAL_RESTART_PHASE_ATTEMPTS:-3}

response_file=$(mktemp)
trap 'rm -f "$response_file"' EXIT

submit_ticket() {
  target=$1
  payload=$(jq -cn --arg repositoryUrl "$repository_url" --arg requirement "$requirement Phase: $target." \
    '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement,routingFacts:{qualification:"QUALIFIED",risk:"R1",modules:1,domains:1,estimatedFiles:2,independentCodeScopes:1,impacts:[],materialDecisionOpen:false,inputsComplete:true,contradictory:false,budgetAvailable:true}}')
  curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
    -H 'Content-Type: application/json' --data "$payload" >"$response_file"
  task_id=$(jq -er '.id' "$response_file")
  attempt_id=$(jq -er '.workflowAttemptId' "$response_file")
  run_id=$(jq -er '.workflowRunId' "$response_file")
  workflow_id="ai-factory/${task_id}/${attempt_id}"
}

wait_for_phase() {
  target=$1
  "${compose[@]}" exec -T orchestrator-db sh -c '
    task_id=$1
    target=$2
    database_user=$3
    database_name=$4
    deadline=$(( $(date +%s) + $5 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      status=$(psql -U "$database_user" -d "$database_name" -Atc \
        "SELECT status FROM tasks WHERE task_id = '\''${task_id}'\''")
      [ "$status" = "$target" ] && exit 0
      case "$status" in
        GATE_REJECTED)
          echo "Task $task_id was rejected by a functional gate before phase $target." >&2
          exit 10
          ;;
        FAILED|CANCELLED|PR_CREATED|WAITING_APPROVAL)
          [ "$target" = "WAITING_APPROVAL" ] && exit 0
          if [ "$target" = "CANCELLED" ] && [ "$status" = "WAITING_APPROVAL" ]; then
            sleep 0.1
            continue
          fi
          echo "Task $task_id reached terminal status $status before phase $target." >&2
          exit 1
          ;;
      esac
      sleep 0.1
    done
    echo "Task $task_id did not reach phase $target within ${5}s." >&2
    exit 1
  ' sh "$task_id" "$target" "$database_user" "$database_name" "$phase_timeout"
}

for phase in $phases; do
  phase_ready=false
  for qualification_attempt in $(seq 1 "$phase_attempts"); do
    submit_ticket "$phase"
    if wait_for_phase "$phase"; then
      phase_ready=true
      break
    else
      result=$?
      if [ "$result" -ne 10 ] || [ "$qualification_attempt" -eq "$phase_attempts" ]; then
        exit "$result"
      fi
      echo "Retrying phase $phase after functional gate rejection ($qualification_attempt/$phase_attempts)..."
    fi
  done
  [ "$phase_ready" = true ] || exit 1
  echo "Recreating orchestrator during phase $phase for task $task_id..."
  "${compose[@]}" up -d --force-recreate orchestrator temporal-worker-activation >/dev/null 2>&1
  ./scripts/wait-compose-job.sh temporal-worker-activation 120 >/dev/null 2>&1
  projected_run_id=$("${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" \
    -Atc "SELECT temporal_run_id FROM workflow_runs WHERE task_id = '${task_id}' AND attempt_id = '${attempt_id}'" \
    | tr -d '[:space:]')
  [ "$projected_run_id" = "$run_id" ] || {
    echo "Temporal Run ID changed after orchestrator recreation in phase $phase." >&2
    exit 1
  }
  cancel_payload=$(jq -cn --arg phase "$phase" \
    '{reason:("restart qualification completed for " + $phase),actor:"temporal-compose-test"}')
  curl -fsS --max-time 30 -X POST \
    "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/cancel" \
    -H 'Content-Type: application/json' --data "$cancel_payload" >/dev/null
  wait_for_phase CANCELLED
  echo "Phase $phase verified: workflow=$workflow_id run=$run_id"
done

echo "Temporal orchestrator recreation verified across critical phases: [$phases]"
