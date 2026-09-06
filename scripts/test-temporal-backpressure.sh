#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}
repository_url=${TEMPORAL_TEST_REPOSITORY_URL:-http://gitea:3000/${GITEA_ADMIN_USER:-aiadmin}/customer-api.git}
ticket_count=${TEMPORAL_BACKPRESSURE_TICKETS:-6}
constrained_capacity=${TEMPORAL_BACKPRESSURE_CAPACITY:-1}
constrained_rate=${TEMPORAL_BACKPRESSURE_ACTIVITIES_PER_SECOND:-0.1}

if ! [[ "$ticket_count" =~ ^[2-9][0-9]*$ ]] || ! [[ "$constrained_capacity" =~ ^[1-9][0-9]*$ ]] \
    || [ "$ticket_count" -le "$constrained_capacity" ]; then
  echo "Backpressure test requires a ticket count strictly greater than constrained worker capacity." >&2
  exit 2
fi
temp_dir=$(mktemp -d)
restored=false
task_ids=()

restore_worker() {
  for task_id in "${task_ids[@]}"; do
    cancel_payload=$(jq -cn '{reason:"backpressure qualification cleanup",actor:"temporal-load-test"}')
    curl -fsS --max-time 10 -X POST \
      "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/cancel" \
      -H 'Content-Type: application/json' --data "$cancel_payload" >/dev/null 2>&1 || true
  done
  if [ "$restored" = false ]; then
    "${compose[@]}" unpause orchestrator >/dev/null 2>&1 || true
    "${compose[@]}" up -d --force-recreate orchestrator temporal-worker-activation >/dev/null 2>&1 || true
  fi
  rm -rf "$temp_dir"
}
trap restore_worker EXIT

echo "Constraining every Temporal worker to capacity=$constrained_capacity rate=${constrained_rate}/s"
AI_FACTORY_TEMPORAL_MAX_CONCURRENT_ACTIVITIES=$constrained_capacity \
AI_FACTORY_TEMPORAL_MAX_TASK_QUEUE_ACTIVITIES_PER_SECOND=$constrained_rate \
  "${compose[@]}" up -d --force-recreate orchestrator temporal-worker-activation >/dev/null
./scripts/wait-compose-job.sh temporal-worker-activation 120 >/dev/null

payload=$(jq -cn --arg repositoryUrl "$repository_url" \
  '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:"Temporal backpressure qualification: add one concise test comment without changing behavior."}')
started_ms=$(python3 -c 'import time; print(round(time.time() * 1000))')
pids=()
for sequence in $(seq 1 "$ticket_count"); do
  curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
    -H 'Content-Type: application/json' --data "$payload" >"$temp_dir/task-$sequence.json" &
  pids+=("$!")
done
for pid in "${pids[@]}"; do
  wait "$pid"
done
admitted_ms=$(python3 -c 'import time; print(round(time.time() * 1000))')
admission_duration_ms=$((admitted_ms - started_ms))

for response in "$temp_dir"/task-*.json; do
  task_ids+=("$(jq -er '.id' "$response")")
  jq -e '.workflowAttemptId == "pipeline-1" and (.workflowRunId | length > 0)' "$response" >/dev/null
done
[ "${#task_ids[@]}" -eq "$ticket_count" ] || exit 1

queues=(
  context
  llm
  workflow
  sandbox
)
max_backlog=0
backlog_queue=none
deadline=$(( $(date +%s) + 90 ))
while [ "$(date +%s)" -lt "$deadline" ] && [ "$max_backlog" -eq 0 ]; do
  for queue in "${queues[@]}"; do
    metric=$(curl -fsS --max-time 5 \
      "http://127.0.0.1:${orchestrator_port}/actuator/metrics/ai_temporal_task_queue_backlog?tag=perimeter:${queue}&tag=task_type:activity")
    backlog=$(jq -r '.measurements[0].value | floor' <<<"$metric")
    if [ "$backlog" -gt "$max_backlog" ]; then
      max_backlog=$backlog
      backlog_queue=$queue
    fi
    [ "$max_backlog" -gt 0 ] && break
  done
  [ "$max_backlog" -gt 0 ] || sleep 2
done
[ "$max_backlog" -gt 0 ] || {
  echo "No Temporal task queue backlog was observed under constrained capacity." >&2
  exit 1
}

for task_id in "${task_ids[@]}"; do
  cancel_payload=$(jq -cn '{reason:"backpressure qualification completed",actor:"temporal-load-test"}')
  curl -fsS --max-time 30 -X POST \
    "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/cancel" \
    -H 'Content-Type: application/json' --data "$cancel_payload" >/dev/null
done

"${compose[@]}" up -d --force-recreate orchestrator temporal-worker-activation >/dev/null
./scripts/wait-compose-job.sh temporal-worker-activation 120 >/dev/null
restored=true

echo "Temporal backpressure verified: admitted=$ticket_count capacity=$constrained_capacity admission_ms=$admission_duration_ms max_backlog=$max_backlog queue=$backlog_queue"
