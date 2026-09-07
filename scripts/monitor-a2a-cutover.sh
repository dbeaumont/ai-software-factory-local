#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
services=(orchestrator "${roles[@]/#/a2a-}")
duration=${A2A_HYPERCARE_SECONDS:-300}
interval=${A2A_HYPERCARE_INTERVAL_SECONDS:-15}
expected_runtime_image=${EXPECTED_A2A_RUNTIME_IMAGE_ID:?EXPECTED_A2A_RUNTIME_IMAGE_ID is required}
expected_orchestrator_image=${EXPECTED_ORCHESTRATOR_IMAGE_ID:?EXPECTED_ORCHESTRATOR_IMAGE_ID is required}
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
a2a_database_name=${AI_FACTORY_A2A_TASK_DB_NAME:-ai_factory_a2a}
a2a_database_user=${AI_FACTORY_A2A_TASK_DB_USERNAME:-ai_factory_a2a}
max_active=${AI_FACTORY_A2A_MAX_TASKS_PER_TENANT:-64}
max_queued=${AI_FACTORY_A2A_MAX_QUEUED_TASKS:-1000}

[[ "$duration" =~ ^[0-9]+$ ]] && [ "$duration" -ge 60 ] || {
  echo "A2A_HYPERCARE_SECONDS must be an integer of at least 60" >&2
  exit 2
}
[[ "$interval" =~ ^[0-9]+$ ]] && [ "$interval" -ge 5 ] || {
  echo "A2A_HYPERCARE_INTERVAL_SECONDS must be an integer of at least 5" >&2
  exit 2
}
[[ "$expected_runtime_image" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  echo "EXPECTED_A2A_RUNTIME_IMAGE_ID must be a sha256 image ID" >&2
  exit 2
}
[[ "$expected_orchestrator_image" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  echo "EXPECTED_ORCHESTRATOR_IMAGE_ID must be a sha256 image ID" >&2
  exit 2
}

fail_closed() {
  local reason=$1
  echo "A2A stabilization threshold breached: $reason" >&2
  ./scripts/set-admissions.sh close >&2 || true
  exit 1
}

unexpected_failure() {
  local exit_code=$?
  trap - ERR
  fail_closed "monitor command failed with exit code $exit_code"
}
trap unexpected_failure ERR

a2a_scalar() {
  "${compose[@]}" exec -T a2a-task-db psql -U "$a2a_database_user" -d "$a2a_database_name" -Atc "$1" \
    | tr -d '[:space:]'
}

orchestrator_scalar() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc "$1" \
    | tr -d '[:space:]'
}

container_id() {
  local service=$1
  local id
  id=$("${compose[@]}" ps -q "$service")
  [ -n "$id" ] || fail_closed "$service is not running"
  printf '%s' "$id"
}

baseline_restarts=()
for service in "${services[@]}"; do
  id=$(container_id "$service")
  baseline_restarts+=("$(docker inspect --format '{{.RestartCount}}' "$id")")
done

capabilities=$(curl -fsS --max-time 5 "http://127.0.0.1:${orchestrator_port}/api/capabilities")
expected_revision=${EXPECTED_ADMISSION_REVISION:-$(jq -er '.admissionRevision' <<<"$capabilities")}
[[ "$expected_revision" =~ ^[0-9]+$ ]] || fail_closed "invalid admission revision"
[ "$(jq -r '.admissionsOpen' <<<"$capabilities")" = true ] || fail_closed "global admissions are not open"

make a2a-smoke >/dev/null || fail_closed "initial Agent Card or runtime smoke failed"
./scripts/test-temporal-compose-readiness.sh >/dev/null || fail_closed "initial Temporal readiness failed"
./scripts/check-signoz-telemetry.sh >/dev/null || fail_closed "initial SigNoz telemetry check failed"

started_epoch=$(date +%s)
started_rfc3339=$(date -u +%Y-%m-%dT%H:%M:%SZ)
samples=0

check_sample() {
  local service id health image restarts oom index
  index=0
  for service in "${services[@]}"; do
    id=$(container_id "$service")
    health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")
    [ "$health" = healthy ] || fail_closed "$service is not healthy: $health"
    image=$(docker inspect --format '{{.Image}}' "$id")
    if [ "$service" = orchestrator ]; then
      [ "$image" = "$expected_orchestrator_image" ] || fail_closed "orchestrator image drift"
    else
      [ "$image" = "$expected_runtime_image" ] || fail_closed "$service image drift"
    fi
    restarts=$(docker inspect --format '{{.RestartCount}}' "$id")
    [ "$restarts" = "${baseline_restarts[$index]}" ] || fail_closed "$service restarted during stabilization"
    oom=$(docker inspect --format '{{.State.OOMKilled}}' "$id")
    [ "$oom" = false ] || fail_closed "$service was OOM-killed"
    index=$((index + 1))
  done

  readiness=$(curl -fsS --max-time 10 "http://127.0.0.1:${orchestrator_port}/actuator/health/readiness")
  jq -e '.status == "UP" and .components.a2aFleet.status == "UP"
    and (.components.a2aFleet.details.blockers | length == 0)' <<<"$readiness" >/dev/null \
    || fail_closed "application or A2A fleet readiness is not UP"
  capabilities=$(curl -fsS --max-time 5 "http://127.0.0.1:${orchestrator_port}/api/capabilities")
  [ "$(jq -r '.admissionsOpen' <<<"$capabilities")" = true ] || fail_closed "global admissions closed"
  [ "$(jq -r '.admissionRevision' <<<"$capabilities")" = "$expected_revision" ] \
    || fail_closed "admission revision drift"

  stale_notifications=$(a2a_scalar \
    "SELECT count(*) FROM a2a_agent_notification_outbox WHERE acknowledged_at IS NULL AND occurred_at < now() - interval '60 seconds'")
  [ "$stale_notifications" = 0 ] || fail_closed "$stale_notifications stale A2A notification(s)"
  stale_cancellations=$(a2a_scalar \
    "SELECT count(*) FROM a2a_agent_cancellation_outbox WHERE acknowledged_at IS NULL AND occurred_at < now() - interval '60 seconds'")
  [ "$stale_cancellations" = 0 ] || fail_closed "$stale_cancellations stale A2A cancellation(s)"
  overloaded_tenants=$(a2a_scalar \
    "SELECT count(*) FROM (SELECT agent_role, tenant_id FROM a2a_agent_task WHERE task_state NOT IN ('COMPLETED','REJECTED','FAILED','CANCELED') GROUP BY agent_role, tenant_id HAVING count(*) > ${max_active}) bounded")
  [ "$overloaded_tenants" = 0 ] || fail_closed "$overloaded_tenants role/tenant active-capacity breach(es)"
  overloaded_queues=$(a2a_scalar \
    "SELECT count(*) FROM (SELECT agent_role FROM a2a_agent_task WHERE task_state = 'SUBMITTED' GROUP BY agent_role HAVING count(*) > ${max_queued}) bounded")
  [ "$overloaded_queues" = 0 ] || fail_closed "$overloaded_queues queue-capacity breach(es)"
  duplicate_messages=$(a2a_scalar \
    "SELECT count(*) FROM (SELECT message_id FROM a2a_agent_task_message GROUP BY message_id HAVING count(*) > 1) duplicates")
  [ "$duplicate_messages" = 0 ] || fail_closed "$duplicate_messages duplicate message identity(ies)"
  recent_agent_failures=$(a2a_scalar \
    "SELECT count(*) FROM a2a_agent_task WHERE task_state IN ('FAILED','REJECTED') AND submitted_at >= to_timestamp(${started_epoch})")
  [ "$recent_agent_failures" = 0 ] || fail_closed "$recent_agent_failures A2A task failure(s)"
  stale_inbox=$(orchestrator_scalar \
    "SELECT count(*) FROM a2a_notification_inbox WHERE signal_status = 'PENDING' AND occurred_at >= to_timestamp(${started_epoch}) AND occurred_at < now() - interval '60 seconds'")
  [ "$stale_inbox" = 0 ] || fail_closed "$stale_inbox stale orchestrator inbox notification(s)"
  recent_task_failures=$(orchestrator_scalar \
    "SELECT count(*) FROM tasks WHERE status = 'FAILED' AND updated_at >= to_timestamp(${started_epoch})")
  [ "$recent_task_failures" = 0 ] || fail_closed "$recent_task_failures projected task failure(s)"
  usage=$(orchestrator_scalar \
    "SELECT COALESCE(sum(tokens_used),0) || ':' || COALESCE(sum(cost_micros),0) FROM budget_usage WHERE recorded_at >= to_timestamp(${started_epoch})")
  active_tasks=$(a2a_scalar \
    "SELECT count(*) FROM a2a_agent_task WHERE task_state NOT IN ('COMPLETED','REJECTED','FAILED','CANCELED')")
  backlog=$(a2a_scalar "SELECT count(*) FROM a2a_agent_task WHERE task_state = 'SUBMITTED'")
  samples=$((samples + 1))
  printf 'a2a-stabilization sample=%d elapsed_seconds=%d active=%s backlog=%s stale_notifications=%s stale_inbox=%s usage_tokens_cost_micros=%s\n' \
    "$samples" "$(( $(date +%s) - started_epoch ))" "$active_tasks" "$backlog" \
    "$stale_notifications" "$stale_inbox" "$usage"
}

deadline=$((started_epoch + duration))
while [ "$(date +%s)" -lt "$deadline" ]; do
  check_sample
  remaining=$((deadline - $(date +%s)))
  [ "$remaining" -le 0 ] && break
  [ "$remaining" -lt "$interval" ] && sleep "$remaining" || sleep "$interval"
done
check_sample

log_violations=$("${compose[@]}" logs --since "$started_rfc3339" orchestrator "${roles[@]/#/a2a-}" \
  | awk '/Divergent A2A notification|Out-of-order A2A notification|idempotency collision/{count++} END {print count+0}')
[ "$log_violations" = 0 ] || fail_closed "$log_violations divergence/idempotency violation(s) in logs"

monitored_containers=()
for service in "${services[@]}"; do
  monitored_containers+=("$(container_id "$service")")
done
memory_breaches=$(docker stats --no-stream --format '{{.Name}}|{{.MemPerc}}' "${monitored_containers[@]}" \
  | awk -F'[|%]' '$2 + 0 >= 95 {count++} END {print count+0}')
[ "$memory_breaches" = 0 ] || fail_closed "$memory_breaches container(s) at or above 95% memory"

make a2a-smoke >/dev/null || fail_closed "final Agent Card or runtime smoke failed"
./scripts/test-temporal-compose-readiness.sh >/dev/null || fail_closed "final Temporal readiness failed"
./scripts/check-signoz-telemetry.sh >/dev/null || fail_closed "final SigNoz telemetry check failed"

printf 'A2A stabilization passed: duration_seconds=%d samples=%d admission_revision=%s runtime_image=%s orchestrator_image=%s log_violations=%s memory_breaches=%s\n' \
  "$(( $(date +%s) - started_epoch ))" "$samples" "$expected_revision" "$expected_runtime_image" \
  "$expected_orchestrator_image" "$log_violations" "$memory_breaches"
