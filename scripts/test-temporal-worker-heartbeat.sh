#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}
repository_url=${TEMPORAL_TEST_REPOSITORY_URL:-http://gitea:3000/${GITEA_ADMIN_USER:-aiadmin}/customer-api.git}
requirement=${TEMPORAL_HEARTBEAT_TEST_REQUIREMENT:-Temporal heartbeat verification: add one concise comment to the customer not-found test without changing behavior.}
timeout=${TEMPORAL_HEARTBEAT_TEST_TIMEOUT_SECONDS:-1200}
qualification_attempts=${TEMPORAL_HEARTBEAT_TEST_ATTEMPTS:-3}

response_file=$(mktemp)
describe_file=$(mktemp)
history_file=$(mktemp)
runner_paused=false
worker_killed=false

wait_service_healthy() {
  service=$1
  deadline=$(( $(date +%s) + ${2:-60} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    health=$("${compose[@]}" ps --format json "$service" 2>/dev/null \
      | jq -sr '.[0].Health // empty')
    [ "$health" = healthy ] && return 0
    sleep 1
  done
  echo "Compose service $service did not become healthy." >&2
  return 1
}

cleanup() {
  rm -f "$response_file" "$describe_file" "$history_file"
  if [ "$runner_paused" = true ]; then
    "${compose[@]}" unpause sandbox-runner-dependency >/dev/null 2>&1 || true
    wait_service_healthy sandbox-runner-dependency 60 >/dev/null 2>&1 || true
  fi
  if [ "$worker_killed" = true ]; then
    "${compose[@]}" up -d orchestrator temporal-worker-activation >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

task_status() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT status FROM tasks WHERE task_id = '${task_id}'" | tr -d '[:space:]'
}

wait_for_testing() {
  "${compose[@]}" exec -T orchestrator-db sh -c '
    task_id=$1
    database_user=$2
    database_name=$3
    deadline=$(( $(date +%s) + $4 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      status=$(psql -U "$database_user" -d "$database_name" -Atc \
        "SELECT status FROM tasks WHERE task_id = '\''${task_id}'\''")
      [ "$status" = TESTING ] && exit 0
      case "$status" in
        GATE_REJECTED) exit 10 ;;
        FAILED|CANCELLED|PR_CREATED|WAITING_APPROVAL)
          echo "Task $task_id reached $status before TESTING." >&2
          exit 1
          ;;
      esac
      sleep 0.1
    done
    echo "Task $task_id did not reach TESTING within ${4}s." >&2
    exit 1
  ' sh "$task_id" "$database_user" "$database_name" "$timeout"
}

wait_for_running_test_job() {
  "${compose[@]}" exec -T sandbox-execution-mcp sh -c '
    task_id=$1
    deadline=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
      for snapshot in /var/lib/ai-factory/sandbox-jobs/*.json; do
        [ -f "$snapshot" ] || continue
        if grep -q "\"task_id\":\"${task_id}\"" "$snapshot" \
            && grep -q "\"operation\":\"RUN_TESTS\"" "$snapshot" \
            && grep -Eq "\"status\":\"(ACCEPTED|RUNNING)\"" "$snapshot"; then
          basename "$snapshot" .json
          exit 0
        fi
      done
      sleep 0.1
    done
    echo "No running RUN_TESTS sandbox job found for task $task_id." >&2
    exit 1
  ' sh "$task_id"
}

wait_for_heartbeat_detail() {
  deadline=$(( $(date +%s) + 15 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if "${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-worker-activation \
        workflow describe --address temporal:7233 --namespace "$namespace" \
        --workflow-id "$workflow_id" --run-id "$run_id" --output json \
        >"$describe_file" 2>/dev/null && grep -q "$execution_id" "$describe_file"; then
      return 0
    fi
    sleep 1
  done
  echo "Temporal did not expose sandbox execution $execution_id in heartbeat details." >&2
  return 1
}

wait_until_resumed() {
  deadline=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    status=$(task_status)
    case "$status" in
      QUALITY_SCANNING|SECURITY_SCANNING|REVIEWING|WAITING_APPROVAL|GATE_REJECTED|PR_CREATED) return 0 ;;
      FAILED|CANCELLED)
        echo "Task $task_id reached $status instead of resuming TESTING." >&2
        return 1
        ;;
    esac
    sleep 1
  done
  echo "Task $task_id did not leave TESTING after worker restart." >&2
  return 1
}

"${compose[@]}" pause sandbox-runner-dependency >/dev/null
runner_paused=true

testing_reached=false
for qualification_attempt in $(seq 1 "$qualification_attempts"); do
  payload=$(jq -cn --arg repositoryUrl "$repository_url" --arg requirement "$requirement Attempt: $qualification_attempt." \
    '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement}')
  curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
    -H 'Content-Type: application/json' --data "$payload" >"$response_file"
  task_id=$(jq -er '.id' "$response_file")
  attempt_id=$(jq -er '.workflowAttemptId' "$response_file")
  run_id=$(jq -er '.workflowRunId' "$response_file")
  workflow_id="ai-factory/${task_id}/${attempt_id}"
  if wait_for_testing; then
    testing_reached=true
    break
  else
    result=$?
    if [ "$result" -ne 10 ] || [ "$qualification_attempt" -eq "$qualification_attempts" ]; then
      exit "$result"
    fi
    echo "Retrying after functional gate rejection ($qualification_attempt/$qualification_attempts)..."
  fi
done
[ "$testing_reached" = true ] || exit 1

execution_id=$(wait_for_running_test_job)
wait_for_heartbeat_detail

echo "Killing the worker during RUN_TESTS: task=$task_id execution=$execution_id"
"${compose[@]}" kill -s KILL orchestrator >/dev/null
worker_killed=true
"${compose[@]}" unpause sandbox-runner-dependency >/dev/null
wait_service_healthy sandbox-runner-dependency 60
runner_paused=false
"${compose[@]}" up -d orchestrator temporal-worker-activation >/dev/null
./scripts/wait-compose-job.sh temporal-worker-activation 120 >/dev/null
worker_killed=false

wait_until_resumed

"${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-worker-activation \
  workflow show --address temporal:7233 --namespace "$namespace" \
  --workflow-id "$workflow_id" --run-id "$run_id" --output json >"$history_file" 2>/dev/null
heartbeat_retry_count=$(jq '[.events[]
  | select(.eventType == "EVENT_TYPE_ACTIVITY_TASK_STARTED")
  | select((.activityTaskStartedEventAttributes.attempt // 1) >= 2)
  | select(.activityTaskStartedEventAttributes.lastFailure.timeoutFailureInfo.timeoutType
      == "TIMEOUT_TYPE_HEARTBEAT")] | length' "$history_file")
[ "$heartbeat_retry_count" -ge 1 ] || {
  echo "Temporal history contains no second activity attempt after heartbeat timeout." >&2
  exit 1
}

test_job_count=$("${compose[@]}" exec -T sandbox-execution-mcp sh -c '
  task_id=$1
  count=0
  for snapshot in /var/lib/ai-factory/sandbox-jobs/*.json; do
    [ -f "$snapshot" ] || continue
    if grep -q "\"task_id\":\"${task_id}\"" "$snapshot" \
        && grep -q "\"operation\":\"RUN_TESTS\"" "$snapshot"; then
      count=$((count + 1))
    fi
  done
  echo "$count"
' sh "$task_id" | tr -d '[:space:]')
[ "$test_job_count" = 1 ] || {
  echo "Expected one RUN_TESTS sandbox submission, found $test_job_count." >&2
  exit 1
}

projected_run_id=$("${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" \
  -Atc "SELECT temporal_run_id FROM workflow_runs WHERE task_id = '${task_id}' AND attempt_id = '${attempt_id}'" \
  | tr -d '[:space:]')
[ "$projected_run_id" = "$run_id" ] || {
  echo "Temporal Run ID changed after sandbox worker restart." >&2
  exit 1
}

status=$(task_status)
case "$status" in
  GATE_REJECTED|PR_CREATED) ;;
  *)
    cancel_payload=$(jq -cn '{reason:"heartbeat qualification completed",actor:"temporal-compose-test"}')
    curl -fsS --max-time 30 -X POST \
      "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/cancel" \
      -H 'Content-Type: application/json' --data "$cancel_payload" >/dev/null
    ;;
esac

echo "Temporal sandbox heartbeat resume verified: workflow=$workflow_id run=$run_id execution=$execution_id submissions=1"
