#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}

history_before=$(mktemp)
history_after_temporal=$(mktemp)
history_after_database=$(mktemp)

cleanup() {
  rm -f "$history_before" "$history_after_temporal" "$history_after_database"
  "${compose[@]}" up -d temporal-db temporal >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_service_healthy() {
  service=$1
  deadline=$(( $(date +%s) + ${2:-120} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    health=$("${compose[@]}" ps --format json "$service" 2>/dev/null | jq -sr '.[0].Health // empty')
    [ "$health" = healthy ] && return 0
    sleep 1
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
  echo "Temporal history $workflow_id/$run_id was not readable after restart." >&2
  return 1
}

history_digest() {
  jq -S -c . "$1" | shasum -a 256 | awk '{print $1}'
}

projection() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT tasks.task_id || '|' || tasks.status || '|' || workflow_runs.workflow_id || '|' ||
      workflow_runs.temporal_run_id || '|' || workflow_runs.attempt_id
     FROM tasks JOIN workflow_runs USING(task_id)
     WHERE tasks.task_id = '${task_id}' AND workflow_runs.temporal_run_id = '${run_id}'"
}

row=$("${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
  "SELECT workflow_id || '|' || temporal_run_id || '|' || task_id || '|' || attempt_id
   FROM workflow_runs
   WHERE status IN ('CANCELLED','GATE_REJECTED','PR_CREATED') AND temporal_run_id IS NOT NULL
   ORDER BY updated_at DESC LIMIT 1")
[ -n "$row" ] || {
  echo "No closed Temporal workflow is available as a persistence fixture." >&2
  exit 1
}
IFS='|' read -r workflow_id run_id task_id attempt_id <<<"$row"

temporal_db_container=$("${compose[@]}" ps -q temporal-db)
[ -n "$temporal_db_container" ] || {
  echo "Temporal PostgreSQL container is not running." >&2
  exit 1
}
volume_name=$(docker inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' \
  "$temporal_db_container")
[ -n "$volume_name" ] || {
  echo "Temporal PostgreSQL data volume was not found." >&2
  exit 1
}
volume_created_at=$(docker volume inspect --format '{{.CreatedAt}}' "$volume_name")

fetch_history "$history_before"
expected_history_digest=$(history_digest "$history_before")
expected_event_count=$(jq '.events | length' "$history_before")
expected_projection=$(projection)

echo "Restarting Temporal service for workflow=$workflow_id run=$run_id"
"${compose[@]}" restart temporal >/dev/null
wait_service_healthy temporal
fetch_history "$history_after_temporal"
[ "$(history_digest "$history_after_temporal")" = "$expected_history_digest" ] || {
  echo "Temporal history changed after the Temporal service restart." >&2
  exit 1
}

echo "Restarting Temporal PostgreSQL while retaining volume=$volume_name"
"${compose[@]}" restart temporal-db >/dev/null
wait_service_healthy temporal-db
fetch_history "$history_after_database"

temporal_db_container_after=$("${compose[@]}" ps -q temporal-db)
volume_name_after=$(docker inspect --format \
  '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}' \
  "$temporal_db_container_after")
volume_created_at_after=$(docker volume inspect --format '{{.CreatedAt}}' "$volume_name_after")
[ "$volume_name_after" = "$volume_name" ] && [ "$volume_created_at_after" = "$volume_created_at" ] || {
  echo "Temporal PostgreSQL data volume identity changed across restart." >&2
  exit 1
}
[ "$(history_digest "$history_after_database")" = "$expected_history_digest" ] || {
  echo "Temporal history changed after the PostgreSQL restart." >&2
  exit 1
}
[ "$(jq '.events | length' "$history_after_database")" = "$expected_event_count" ] || {
  echo "Temporal history event count changed after the PostgreSQL restart." >&2
  exit 1
}
[ "$(projection)" = "$expected_projection" ] || {
  echo "Orchestrator projection changed across Temporal storage restarts." >&2
  exit 1
}

echo "Temporal storage restart verified: workflow=$workflow_id run=$run_id events=$expected_event_count volume=$volume_name"
