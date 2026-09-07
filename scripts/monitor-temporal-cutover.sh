#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
duration=${TEMPORAL_HYPERCARE_SECONDS:-300}
interval=${TEMPORAL_HYPERCARE_INTERVAL_SECONDS:-15}
expected_image=${EXPECTED_ORCHESTRATOR_IMAGE_ID:?EXPECTED_ORCHESTRATOR_IMAGE_ID is required}
rollback_image=${ROLLBACK_ORCHESTRATOR_IMAGE_ID:?ROLLBACK_ORCHESTRATOR_IMAGE_ID is required}
backup_directory=${CUTOVER_BACKUP_DIR:?CUTOVER_BACKUP_DIR is required}
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}

[[ "$duration" =~ ^[0-9]+$ ]] && [ "$duration" -ge 60 ] || {
  echo "TEMPORAL_HYPERCARE_SECONDS must be an integer of at least 60" >&2
  exit 2
}
[[ "$interval" =~ ^[0-9]+$ ]] && [ "$interval" -ge 5 ] || {
  echo "TEMPORAL_HYPERCARE_INTERVAL_SECONDS must be an integer of at least 5" >&2
  exit 2
}
[[ "$expected_image" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "Invalid expected image ID" >&2; exit 2; }
[[ "$rollback_image" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo "Invalid rollback image ID" >&2; exit 2; }
[ -f "$backup_directory/manifest.sha256" ] || { echo "Cutover backup manifest is unavailable" >&2; exit 2; }
[ -f docs/operations/runbooks/ROLLBACK-TEMPORAL.md ] || { echo "Temporal rollback runbook is unavailable" >&2; exit 2; }
docker image inspect "$rollback_image" >/dev/null

fail_closed() {
  reason=$1
  echo "Temporal hypercare threshold breached: $reason" >&2
  ./scripts/set-admissions.sh close >&2 || true
  exit 1
}

unexpected_failure() {
  exit_code=$?
  trap - ERR
  fail_closed "monitor command failed with exit code $exit_code"
}
trap unexpected_failure ERR

temporal_failed_count() {
  "${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-namespace workflow list \
    --namespace "$namespace" --address temporal:7233 --query 'ExecutionStatus="Failed"' --output json \
    | jq 'length'
}

check_sample() {
  active_image=$(docker inspect ai-factory-orchestrator-1 --format '{{.Image}}')
  [ "$active_image" = "$expected_image" ] || fail_closed "active orchestrator image drift"
  [ "$(docker inspect ai-factory-orchestrator-1 --format '{{.State.Health.Status}}')" = healthy ] \
    || fail_closed "orchestrator is not healthy"
  [ "$(docker inspect ai-factory-temporal-1 --format '{{.State.Health.Status}}')" = healthy ] \
    || fail_closed "Temporal is not healthy"
  [ "$(docker inspect ai-factory-evidence-mcp-1 --format '{{.State.Health.Status}}')" = healthy ] \
    || fail_closed "Evidence MCP is not healthy"
  [ "$(docker inspect ai-factory-otel-collector-1 --format '{{.State.Health.Status}}')" = healthy ] \
    || fail_closed "OpenTelemetry Collector is not healthy"
  [ "$(docker inspect ai-factory-signoz-1 --format '{{.State.Health.Status}}')" = healthy ] \
    || fail_closed "SigNoz is not healthy"
  curl -fsS --max-time 5 "http://127.0.0.1:${orchestrator_port}/actuator/health/readiness" \
    | jq -e '.status == "UP"' >/dev/null || fail_closed "application readiness is not UP"
  admissions_open=$(curl -fsS --max-time 5 "http://127.0.0.1:${orchestrator_port}/api/capabilities" \
    | jq -r '.admissionsOpen')
  [ "$admissions_open" = true ] || fail_closed "global admissions are not open"
  stale_admissions=$("${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT count(*) FROM task_admission_outbox WHERE status = 'PENDING' AND created_at < now() - interval '1 minute'" \
    | tr -d '[:space:]')
  [ "$stale_admissions" = 0 ] || fail_closed "$stale_admissions admission(s) pending for more than one minute"
  recent_failures=$("${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT count(*) FROM tasks WHERE status = 'FAILED' AND updated_at >= to_timestamp($started_epoch)" \
    | tr -d '[:space:]')
  [ "$recent_failures" = 0 ] || fail_closed "$recent_failures projected task failure(s) since opening"
  failed_workflows=$(temporal_failed_count)
  [ "$failed_workflows" = "$baseline_failed_workflows" ] \
    || fail_closed "Temporal failed workflow count changed ($baseline_failed_workflows -> $failed_workflows)"
  samples=$((samples + 1))
  printf 'hypercare sample=%d elapsed_seconds=%d stale_admissions=%s new_task_failures=%s failed_workflows=%s\n' \
    "$samples" "$(( $(date +%s) - started_epoch ))" "$stale_admissions" "$recent_failures" "$failed_workflows"
}

(cd "$backup_directory" && if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c manifest.sha256 >/dev/null
else
  shasum -a 256 -c manifest.sha256 >/dev/null
fi) || fail_closed "cutover backup digest verification failed"

./scripts/test-temporal-compose-readiness.sh >/dev/null || fail_closed "namespace, Build ID or poller verification failed"
./scripts/check-signoz-telemetry.sh >/dev/null || fail_closed "SigNoz telemetry verification failed"

started_epoch=$(date +%s)
baseline_failed_workflows=$(temporal_failed_count)
samples=0
deadline=$((started_epoch + duration))
while [ "$(date +%s)" -lt "$deadline" ]; do
  check_sample
  remaining=$((deadline - $(date +%s)))
  [ "$remaining" -le 0 ] && break
  [ "$remaining" -lt "$interval" ] && sleep "$remaining" || sleep "$interval"
done

check_sample
./scripts/test-temporal-compose-readiness.sh >/dev/null || fail_closed "final Temporal readiness verification failed"
./scripts/check-signoz-telemetry.sh >/dev/null || fail_closed "final SigNoz verification failed"

printf 'Temporal hypercare passed: duration_seconds=%d samples=%d image=%s rollback_image=%s backup=%s\n' \
  "$(( $(date +%s) - started_epoch ))" "$samples" "$expected_image" "$rollback_image" "$backup_directory"
