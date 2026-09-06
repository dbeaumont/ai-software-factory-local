#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
repository_url=${TEMPORAL_TEST_REPOSITORY_URL:-http://gitea:3000/${GITEA_ADMIN_USER:-aiadmin}/customer-api.git}

temporal_id=$("${compose[@]}" ps -q temporal)
[ -n "$temporal_id" ] || { echo "Temporal container is missing." >&2; exit 1; }
workflow_network=$(docker inspect "$temporal_id" | jq -er \
  '.[0].NetworkSettings.Networks | keys[] | select(endswith("workflow-internal"))')
disconnected=false

restore_network() {
  if [ "$disconnected" = true ]; then
    docker network connect --alias temporal "$workflow_network" "$temporal_id" >/dev/null 2>&1 || true
  fi
  "${compose[@]}" up -d --force-recreate temporal orchestrator temporal-worker-activation >/dev/null 2>&1 || true
  ./scripts/wait-compose-job.sh temporal-worker-activation 120 >/dev/null 2>&1 || true
}
trap restore_network EXIT

task_count() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    'SELECT count(*) FROM tasks' | tr -d '[:space:]'
}

before=$(task_count)
docker network disconnect "$workflow_network" "$temporal_id"
disconnected=true

unready=false
for attempt in $(seq 1 60); do
  status=$(curl -sS --max-time 5 -o /dev/null -w '%{http_code}' \
    "http://127.0.0.1:${orchestrator_port}/actuator/health/readiness" || true)
  if [ "$status" = 503 ]; then
    unready=true
    break
  fi
  sleep 1
done
[ "$unready" = true ] || { echo "Readiness stayed open during the Temporal network partition." >&2; exit 1; }

payload=$(jq -cn --arg repositoryUrl "$repository_url" \
  '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:"Temporal partition admission must fail closed."}')
admission_status=$(curl -sS --max-time 15 -o /dev/null -w '%{http_code}' -X POST \
  "http://127.0.0.1:${orchestrator_port}/api/tasks" -H 'Content-Type: application/json' --data "$payload" || true)
[ "$admission_status" = 503 ] || {
  echo "Expected HTTP 503 during partition, received $admission_status." >&2
  exit 1
}

docker network connect --alias temporal "$workflow_network" "$temporal_id"
disconnected=false
"${compose[@]}" up -d --force-recreate temporal orchestrator temporal-worker-activation >/dev/null
./scripts/wait-compose-job.sh temporal-worker-activation 120 >/dev/null

curl -fsS --max-time 10 "http://127.0.0.1:${orchestrator_port}/actuator/health/readiness" \
  | jq -e '.status == "UP"' >/dev/null
after=$(task_count)
[ "$after" = "$before" ] || {
  echo "A task was persisted despite the rejected admission ($before -> $after)." >&2
  exit 1
}

trap - EXIT
echo "Temporal network partition verified: readiness=503 admission=503 persisted_tasks=$after recovery=UP"
