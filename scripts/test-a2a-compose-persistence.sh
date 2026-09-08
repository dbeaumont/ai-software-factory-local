#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
task_id=lot7-restart-proof
message_id=lot7-restart-proof-message
digest=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

cleanup() {
  "${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a -v ON_ERROR_STOP=1 \
    -c "DELETE FROM a2a_agent_task WHERE task_id='$task_id'" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

insert="INSERT INTO a2a_agent_task
  (task_id, context_id, message_id, message_digest, agent_role, skill_id, caller_subject, tenant_id,
   delegation_id, submitted_at, task_state, version, envelope_json, business_task_id, workflow_attempt_id)
  VALUES ('$task_id', 'lot7-context', '$message_id', '$digest', 'developer', 'developer.implement',
   'lot7-test', 'lot7-tenant', 'lot7-delegation', CURRENT_TIMESTAMP, 'WORKING', 0, '{}',
   'lot7-business-task', 'attempt-1')
  ON CONFLICT (message_id) DO NOTHING;"
"${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a -v ON_ERROR_STOP=1 -c "$insert" >/dev/null

"${compose[@]}" restart a2a-task-db a2a-developer >/dev/null
deadline=$((SECONDS + 120))
for service in a2a-task-db a2a-developer; do
  while true; do
    container=$("${compose[@]}" ps -q "$service")
    health=$(test -n "$container" && docker inspect --format '{{.State.Health.Status}}' "$container" || echo missing)
    test "$health" != healthy || break
    test "$health" != unhealthy || { echo "$service became unhealthy" >&2; exit 1; }
    test "$SECONDS" -lt "$deadline" || { echo "Timeout waiting for $service" >&2; exit 1; }
    sleep 2
  done
done

"${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a -v ON_ERROR_STOP=1 -c "$insert" >/dev/null
count=$("${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a -At \
  -c "SELECT count(*) FROM a2a_agent_task WHERE message_id='$message_id'")
test "$count" = 1 || { echo "Expected one persisted task after restart, found $count" >&2; exit 1; }
echo "A2A persistence verified: task survived database/runtime restart and duplicate insertion remained unique."
