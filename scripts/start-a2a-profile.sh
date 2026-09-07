#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)

wait_healthy() {
  local deadline=$((SECONDS + ${A2A_START_TIMEOUT_SECONDS:-120}))
  local service container health
  for service in "$@"; do
    while true; do
      container=$("${compose[@]}" ps -q "$service")
      health=missing
      if test -n "$container"; then
        health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")
      fi
      test "$health" != unhealthy || { echo "$service became unhealthy" >&2; exit 1; }
      test "$health" != healthy || break
      test "$SECONDS" -lt "$deadline" || { echo "Timeout waiting for $service health" >&2; exit 1; }
      sleep 2
    done
  done
}

case "${1:-}" in
  role)
    role=${A2A_ROLE:?A2A_ROLE is required}
    allowed=false
    for candidate in "${roles[@]}"; do test "$candidate" != "$role" || allowed=true; done
    test "$allowed" = true || { echo "Unknown A2A role: $role" >&2; exit 2; }
    "${compose[@]}" --profile "a2a-$role" up -d "a2a-$role"
    wait_healthy a2a-identity a2a-task-db "a2a-$role"
    A2A_ROLES="$role" ./scripts/a2a-local.sh smoke
    ;;
  full)
    services=()
    for role in "${roles[@]}"; do services+=("a2a-$role"); done
    "${compose[@]}" --profile a2a-full up -d "${services[@]}"
    wait_healthy a2a-identity a2a-task-db "${services[@]}"
    "${compose[@]}" --profile a2a-full up -d --force-recreate a2a-worker-activation
    ./scripts/wait-compose-job.sh a2a-worker-activation 120
    ./scripts/a2a-local.sh smoke
    ;;
  *) echo "usage: start-a2a-profile.sh {role|full}" >&2; exit 2 ;;
esac
