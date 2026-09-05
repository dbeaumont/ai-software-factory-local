#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
apps=(orchestrator repository-context-mcp sandbox-execution-mcp scm-delivery-mcp assurance-mcp evidence-mcp)

restore() {
  "${compose[@]}" start signoz-ingester otel-collector >/dev/null 2>&1 || true
}
trap restore EXIT

assert_apps_healthy() {
  local service health
  for service in "${apps[@]}"; do
    health=$("${compose[@]}" ps --format json "$service" | jq -er '.Health')
    [ "$health" = "healthy" ] || { echo "$service is not healthy: $health" >&2; exit 1; }
  done
}

wait_service_healthy() {
  local service=$1 health=""
  for attempt in {1..30}; do
    health=$("${compose[@]}" ps --format json "$service" | jq -r '.Health // empty')
    [ "$health" = "healthy" ] && return 0
    sleep 1
  done
  echo "$service did not become healthy: $health" >&2
  return 1
}

wait_service_healthy otel-collector
assert_apps_healthy
"${compose[@]}" stop signoz-ingester >/dev/null
assert_apps_healthy
collector_health=$("${compose[@]}" ps --format json otel-collector | jq -er '.Health')
[ "$collector_health" = "healthy" ] || { echo "Collector failed during backend outage" >&2; exit 1; }
"${compose[@]}" start signoz-ingester >/dev/null
./scripts/check-signoz-telemetry.sh >/dev/null

"${compose[@]}" stop otel-collector >/dev/null
assert_apps_healthy
curl -fsS "http://127.0.0.1:${ORCHESTRATOR_PORT:-8088}/actuator/health/readiness" >/dev/null
"${compose[@]}" start otel-collector >/dev/null
wait_service_healthy otel-collector
./scripts/check-signoz-telemetry.sh >/dev/null

status=$("${compose[@]}" exec -T alert-sink python -c \
  "import urllib.request,urllib.error; r=urllib.request.Request('http://otel-collector:4318/v1/traces',data=b'not-otlp',headers={'Content-Type':'application/json'},method='POST');
try: urllib.request.urlopen(r,timeout=3)
except urllib.error.HTTPError as e: print(e.code)")
[ "$status" = "400" ] || { echo "Invalid OTLP payload returned HTTP $status" >&2; exit 1; }
collector_health=$("${compose[@]}" ps --format json otel-collector | jq -er '.Health')
[ "$collector_health" = "healthy" ] || { echo "Collector failed after invalid OTLP data" >&2; exit 1; }

echo "OpenTelemetry resilience verified: backend outage, Collector outage, recovery and invalid OTLP rejection."
