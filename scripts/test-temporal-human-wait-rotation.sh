#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
source scripts/load-dotenv.sh
load_dotenv .env

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}
deployment=${AI_FACTORY_TEMPORAL_DEPLOYMENT_NAME:-ai-factory-orchestrator}
nominal_build=${AI_FACTORY_TEMPORAL_BUILD_ID:-0.1.0}
rotation_build="${nominal_build}-wait-$(date +%s)"
rotation_container="ai-factory-temporal-wait-rotation"
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
repository_url=${TEMPORAL_TEST_REPOSITORY_URL:-http://gitea:3000/${GITEA_ADMIN_USER:-aiadmin}/customer-api.git}
requirement=${TEMPORAL_HUMAN_WAIT_TEST_REQUIREMENT:-Temporal human wait verification: add one concise comment to the customer not-found test without changing behavior.}
timeout=${TEMPORAL_HUMAN_WAIT_TEST_TIMEOUT_SECONDS:-1200}
qualification_attempts=${TEMPORAL_HUMAN_WAIT_TEST_ATTEMPTS:-3}

response_file=$(mktemp)
task_file=$(mktemp)
task_id=''
completed=false
rotation_current=false

cleanup() {
  if [ "$rotation_current" = true ]; then
    "${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-namespace \
      worker deployment set-current-version --yes --address temporal:7233 \
      --namespace "$namespace" --deployment-name "$deployment" --build-id "$nominal_build" \
      >/dev/null 2>&1 || true
  fi
  docker rm -f "$rotation_container" >/dev/null 2>&1 || true
  if [ -n "$task_id" ] && [ "$completed" != true ]; then
    cancel_payload=$(jq -cn '{reason:"human wait rotation qualification cleanup",actor:"temporal-compose-test"}')
    curl -fsS --max-time 10 -X POST \
      "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/cancel" \
      -H 'Content-Type: application/json' --data "$cancel_payload" >/dev/null 2>&1 || true
  fi
  rm -f "$response_file" "$task_file"
}
trap cleanup EXIT

task_status() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT status FROM tasks WHERE task_id = '${task_id}'" | tr -d '[:space:]'
}

wait_for_status() {
  expected=$1
  deadline=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    status=$(task_status)
    [ "$status" = "$expected" ] && return 0
    case "$status" in
      GATE_REJECTED)
        [ "$expected" = WAITING_APPROVAL ] && return 10
        echo "Task $task_id was rejected while waiting for $expected." >&2
        return 1
        ;;
      FAILED|CANCELLED)
        echo "Task $task_id reached $status while waiting for $expected." >&2
        return 1
        ;;
    esac
    sleep 1
  done
  echo "Task $task_id did not reach $expected within ${timeout}s." >&2
  return 1
}

wait_for_readiness() {
  deadline=$(( $(date +%s) + 180 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if curl -fsS --max-time 5 "http://127.0.0.1:${orchestrator_port}/actuator/health/readiness" \
        | jq -e '.status == "UP"' >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Orchestrator readiness did not recover." >&2
  return 1
}

projected_run_id() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT temporal_run_id FROM workflow_runs WHERE task_id = '${task_id}' AND attempt_id = '${attempt_id}'" \
    | tr -d '[:space:]'
}

temporal_cli=("${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-namespace)

waiting_reached=false
for qualification_attempt in $(seq 1 "$qualification_attempts"); do
  payload=$(jq -cn --arg repositoryUrl "$repository_url" \
    --arg requirement "$requirement Attempt: $qualification_attempt." \
    '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement}')
  curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
    -H 'Content-Type: application/json' --data "$payload" >"$response_file"
  task_id=$(jq -er '.id' "$response_file")
  attempt_id=$(jq -er '.workflowAttemptId' "$response_file")
  run_id=$(jq -er '.workflowRunId' "$response_file")
  if wait_for_status WAITING_APPROVAL; then
    waiting_reached=true
    break
  else
    result=$?
    if [ "$result" -ne 10 ] || [ "$qualification_attempt" -eq "$qualification_attempts" ]; then
      exit "$result"
    fi
  fi
done
[ "$waiting_reached" = true ] || exit 1

waiting_since=$(date +%s)
"${compose[@]}" restart orchestrator >/dev/null
wait_for_readiness
[ "$(task_status)" = WAITING_APPROVAL ]
[ "$(projected_run_id)" = "$run_id" ]

docker rm -f "$rotation_container" >/dev/null 2>&1 || true
"${compose[@]}" run -d --no-deps --name "$rotation_container" \
  -e "AI_FACTORY_TEMPORAL_BUILD_ID=$rotation_build" orchestrator >/dev/null

queues=(
  "${AI_FACTORY_TEMPORAL_WORKFLOW_TASK_QUEUE:-ai-factory-workflows}"
  "${AI_FACTORY_TEMPORAL_CONTEXT_TASK_QUEUE:-ai-factory-context}"
  "${AI_FACTORY_TEMPORAL_LLM_TASK_QUEUE:-ai-factory-llm}"
  "${AI_FACTORY_TEMPORAL_SANDBOX_TASK_QUEUE:-ai-factory-sandbox}"
  "${AI_FACTORY_TEMPORAL_ASSURANCE_TASK_QUEUE:-ai-factory-assurance}"
  "${AI_FACTORY_TEMPORAL_EVIDENCE_TASK_QUEUE:-ai-factory-evidence}"
  "${AI_FACTORY_TEMPORAL_SCM_TASK_QUEUE:-ai-factory-scm}"
)
version=''
version_ready=false
for attempt in $(seq 1 120); do
  if version=$("${temporal_cli[@]}" worker deployment describe-version --address temporal:7233 \
      --namespace "$namespace" --deployment-name "$deployment" --build-id "$rotation_build" 2>/dev/null); then
    version_ready=true
    for queue in "${queues[@]}"; do
      if ! printf '%s\n' "$version" | grep -Fq "$queue"; then
        version_ready=false
        break
      fi
    done
    [ "$version_ready" = true ] && break
  fi
  sleep 1
done
[ "$version_ready" = true ] || {
  echo "Rotated worker version did not register all seven task queues within 120 seconds." >&2
  exit 1
}
"${temporal_cli[@]}" worker deployment set-current-version --yes --address temporal:7233 \
  --namespace "$namespace" --deployment-name "$deployment" --build-id "$rotation_build" >/dev/null
rotation_current=true
[ "$(task_status)" = WAITING_APPROVAL ]
[ "$(projected_run_id)" = "$run_id" ]

curl -fsS --max-time 30 "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}" >"$task_file"
manifest_id=$(jq -er '.pendingEffect.manifestId' "$task_file")
manifest_digest=$(jq -er '.pendingEffect.manifestDigest' "$task_file")
approval_payload=$(jq -cn --arg manifestId "$manifest_id" --arg manifestDigest "$manifest_digest" \
  '{manifestId:$manifestId,manifestDigest:$manifestDigest}')
curl -fsS --max-time 30 -X POST \
  "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/approve-manifest" \
  -H 'Content-Type: application/json' --data "$approval_payload" >/dev/null
wait_for_status PR_CREATED
[ "$(projected_run_id)" = "$run_id" ]

wait_seconds=$(( $(date +%s) - waiting_since ))
completed=true
"${temporal_cli[@]}" worker deployment set-current-version --yes --address temporal:7233 \
  --namespace "$namespace" --deployment-name "$deployment" --build-id "$nominal_build" >/dev/null
rotation_current=false
docker rm -f "$rotation_container" >/dev/null 2>&1 || true

echo "Temporal human wait survived restart and worker rotation: task=$task_id run=$run_id wait_seconds=$wait_seconds old_build=$nominal_build new_build=$rotation_build"
