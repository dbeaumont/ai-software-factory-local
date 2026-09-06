#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
services=(repository-context-mcp litellm gitea sonarqube artifactory)
dependencies=(
  'MCP|repository-context-mcp|http://repository-context-mcp:8091/actuator/health/readiness'
  'LiteLLM|litellm|http://litellm:4000/health/liveliness'
  'Gitea|gitea|http://gitea:3000/api/healthz'
  'SonarQube|sonarqube|http://sonarqube:9000/api/system/status'
  'Artifactory|artifactory|http://artifactory:8082/artifactory/api/system/ping'
)

cleanup() {
  "${compose[@]}" start "${services[@]}" otel-collector signoz-ingester >/dev/null 2>&1 || true
}
trap cleanup EXIT

wait_service_healthy() {
  service=$1
  deadline=$(( $(date +%s) + ${2:-180} ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    health=$("${compose[@]}" ps --format json "$service" 2>/dev/null | jq -sr '.[0].Health // empty')
    [ "$health" = healthy ] && return 0
    sleep 2
  done
  echo "Compose service $service did not become healthy." >&2
  return 1
}

probe_from_orchestrator() {
  url=$1
  "${compose[@]}" exec -T orchestrator curl -fsS --connect-timeout 1 --max-time 3 "$url" >/dev/null
}

wait_probe_down() {
  service=$1
  url=$2
  for attempt in 1 2 3 4 5; do
    if ! probe_from_orchestrator "$url" 2>/dev/null; then
      return 0
    fi
    sleep 1
  done
  echo "$service remained reachable after outage injection." >&2
  return 1
}

assert_control_plane_alive() {
  curl -fsS --connect-timeout 1 --max-time 5 \
    "http://127.0.0.1:${orchestrator_port}/actuator/health/liveness" >/dev/null
}

for dependency in "${dependencies[@]}"; do
  IFS='|' read -r label service url <<<"$dependency"
  wait_service_healthy "$service"
  probe_from_orchestrator "$url"
  echo "Injecting $label outage by stopping $service"
  "${compose[@]}" stop "$service" >/dev/null
  wait_probe_down "$service" "$url"
  assert_control_plane_alive
  if [ "$service" = litellm ]; then
    capabilities=$(curl -fsS --max-time 30 \
      "http://127.0.0.1:${orchestrator_port}/api/capabilities")
    [ "$(jq -r '.cloudAvailable' <<<"$capabilities")" = false ] || {
      echo "The control plane did not report LiteLLM as unavailable." >&2
      exit 1
    }
  fi
  "${compose[@]}" start "$service" >/dev/null
  wait_service_healthy "$service"
  probe_from_orchestrator "$url"
  echo "$label outage and recovery verified"
done

./scripts/test-otel-resilience.sh
assert_control_plane_alive

echo "Dependency outage matrix verified: MCP, LiteLLM, Gitea, SonarQube, Artifactory and Collector."
