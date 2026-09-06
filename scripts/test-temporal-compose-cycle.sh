#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}

history_before=$(mktemp)
history_after=$(mktemp)
stack_down=false

cleanup() {
  rm -f "$history_before" "$history_after"
  if [ "$stack_down" = true ]; then
    "${compose[@]}" up -d --remove-orphans >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_service_healthy() {
  service=$1
  deadline=$(( $(date +%s) + ${2:-240} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    health=$("${compose[@]}" ps --format json "$service" 2>/dev/null | jq -sr '.[0].Health // empty')
    [ "$health" = healthy ] && return 0
    sleep 2
  done
  echo "Compose service $service did not become healthy." >&2
  return 1
}

fetch_history() {
  output=$1
  deadline=$(( $(date +%s) + 120 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if "${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-worker-activation \
        workflow show --address temporal:7233 --namespace "$namespace" \
        --workflow-id "$workflow_id" --run-id "$run_id" --output json \
        >"$output" 2>/dev/null && jq -e '.events | length > 0' "$output" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  echo "Temporal history was not readable after the Compose cycle." >&2
  return 1
}

history_digest() {
  jq -S -c . "$1" | shasum -a 256 | awk '{print $1}'
}

volume_identity() {
  service=$1
  destination=$2
  container=$("${compose[@]}" ps -q "$service")
  volume=$(docker inspect --format \
    "{{range .Mounts}}{{if eq .Destination \"${destination}\"}}{{.Name}}{{end}}{{end}}" "$container")
  created=$(docker volume inspect --format '{{.CreatedAt}}' "$volume")
  printf '%s|%s\n' "$volume" "$created"
}

projection() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT tasks.task_id || '|' || tasks.status || '|' || workflow_runs.workflow_id || '|' ||
      workflow_runs.temporal_run_id || '|' || workflow_runs.attempt_id || '|' ||
      (SELECT count(*) FROM artifacts WHERE artifacts.task_id = tasks.task_id)
     FROM tasks JOIN workflow_runs USING(task_id)
     WHERE tasks.task_id = '${task_id}' AND workflow_runs.temporal_run_id = '${run_id}'"
}

row=$("${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
  "SELECT workflow_id || '|' || temporal_run_id || '|' || task_id || '|' || attempt_id
   FROM workflow_runs WHERE status = 'PR_CREATED' AND temporal_run_id IS NOT NULL
   ORDER BY updated_at DESC LIMIT 1")
[ -n "$row" ] || {
  echo "No delivered Temporal workflow is available as a Compose persistence fixture." >&2
  exit 1
}
IFS='|' read -r workflow_id run_id task_id attempt_id <<<"$row"

fetch_history "$history_before"
expected_history_digest=$(history_digest "$history_before")
expected_event_count=$(jq '.events | length' "$history_before")
expected_projection=$(projection)
temporal_volume=$(volume_identity temporal-db /var/lib/postgresql/data)
orchestrator_volume=$(volume_identity orchestrator-db /var/lib/postgresql/data)

echo "Stopping and removing the Compose stack without deleting named volumes"
"${compose[@]}" down --remove-orphans
stack_down=true

docker volume inspect "${temporal_volume%%|*}" >/dev/null
docker volume inspect "${orchestrator_volume%%|*}" >/dev/null

echo "Recreating the Compose stack from retained volumes"
"${compose[@]}" up -d --remove-orphans
wait_service_healthy temporal-db
wait_service_healthy temporal
wait_service_healthy orchestrator-db
wait_service_healthy orchestrator
./scripts/wait-compose-job.sh temporal-worker-activation 120 >/dev/null
stack_down=false

fetch_history "$history_after"
[ "$(history_digest "$history_after")" = "$expected_history_digest" ] || {
  echo "Temporal history changed across docker compose down/up." >&2
  exit 1
}
[ "$(jq '.events | length' "$history_after")" = "$expected_event_count" ] || {
  echo "Temporal event count changed across docker compose down/up." >&2
  exit 1
}
[ "$(projection)" = "$expected_projection" ] || {
  echo "Task projection changed across docker compose down/up." >&2
  exit 1
}
[ "$(volume_identity temporal-db /var/lib/postgresql/data)" = "$temporal_volume" ] || {
  echo "Temporal PostgreSQL volume identity changed across docker compose down/up." >&2
  exit 1
}
[ "$(volume_identity orchestrator-db /var/lib/postgresql/data)" = "$orchestrator_volume" ] || {
  echo "Orchestrator PostgreSQL volume identity changed across docker compose down/up." >&2
  exit 1
}

task_view=$(curl -fsS --max-time 30 "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}")
[ "$(jq -r '.status' <<<"$task_view")" = PR_CREATED ] \
  && [ "$(jq -r '.workflowRunId' <<<"$task_view")" = "$run_id" ] || {
    echo "Public task view was not restored after docker compose down/up." >&2
    exit 1
  }

echo "Compose persistence verified: task=$task_id run=$run_id events=$expected_event_count volumes=retained"
