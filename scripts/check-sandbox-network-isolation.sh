#!/usr/bin/env bash
set -euo pipefail

COMPOSE=(docker compose --env-file .env -f infrastructure/compose.yaml)

expect_blocked() {
  local service="$1"
  local url="$2"
  if "${COMPOSE[@]}" exec -T "$service" \
      curl -fsS --noproxy '*' --connect-timeout 2 --max-time 3 "$url" >/dev/null 2>&1; then
    echo "Unexpected direct network access from $service to $url" >&2
    exit 1
  fi
}

for runner in sandbox-runner-readonly sandbox-runner-write sandbox-runner-dependency sandbox-runner-quality; do
  "${COMPOSE[@]}" exec -T "$runner" curl -fsS http://localhost:8088/health >/dev/null
  expect_blocked "$runner" http://169.254.169.254/latest/meta-data/
done

expect_blocked sandbox-runner-dependency http://sonarqube:9000/api/system/status
"${COMPOSE[@]}" exec -T sandbox-runner-quality \
  curl -fsS --noproxy '*' --connect-timeout 3 --max-time 10 \
  http://sonarqube:9000/api/system/status >/dev/null

echo "Compose sandbox network isolation verified."
