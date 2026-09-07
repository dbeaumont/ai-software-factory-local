#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)

log() {
  printf '[a2a-start] %s\n' "$*"
}

wait_healthy() {
  local service container health deadline
  for service in "$@"; do
    deadline=$((SECONDS + ${A2A_START_TIMEOUT_SECONDS:-300}))
    while true; do
      container=$("${compose[@]}" ps -q "$service")
      health=missing
      if test -n "$container"; then
        health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")
      fi
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
    log "Starting isolated role $role and its declared dependencies"
    "${compose[@]}" --profile "a2a-$role" up -d "a2a-$role"
    wait_healthy a2a-identity a2a-task-db "a2a-$role"
    log "Role $role is healthy; running identity and Agent Card smoke"
    A2A_ROLES="$role" ./scripts/a2a-local.sh smoke
    ;;
  full)
    services=()
    for role in "${roles[@]}"; do services+=("a2a-$role"); done
    batch_size=${A2A_START_BATCH_SIZE:-4}
    [[ "$batch_size" =~ ^[1-9][0-9]*$ ]] || { echo "A2A_START_BATCH_SIZE must be positive" >&2; exit 2; }
    log "Starting A2A identity and durable task store"
    "${compose[@]}" --profile a2a-full up -d a2a-identity a2a-task-db
    wait_healthy a2a-identity a2a-task-db
    for ((offset = 0; offset < ${#services[@]}; offset += batch_size)); do
      batch=("${services[@]:offset:batch_size}")
      log "Starting runtime batch: ${batch[*]}"
      "${compose[@]}" --profile a2a-full up -d "${batch[@]}"
      wait_healthy "${batch[@]}"
    done
    # Compose assigns new service IPs on runtime replacement. Restart the closed-admission control plane so its
    # DNS-rebinding pins are established against the fully healthy replacement fleet.
    log "Recreating the orchestrator after all agent DNS identities are stable"
    "${compose[@]}" --profile a2a-full up -d --force-recreate orchestrator
    wait_healthy orchestrator
    log "Activating the orchestrator Temporal Build ID"
    "${compose[@]}" --profile a2a-full up -d --force-recreate temporal-worker-activation
    ./scripts/wait-compose-job.sh temporal-worker-activation 120
    log "Activating the 14-role A2A Temporal Build ID"
    "${compose[@]}" --profile a2a-full up -d --force-recreate a2a-worker-activation
    ./scripts/wait-compose-job.sh a2a-worker-activation 120
    log "Running full identity, runtime and Agent Card smoke"
    ./scripts/a2a-local.sh smoke
    ;;
  *) echo "usage: start-a2a-profile.sh {role|full}" >&2; exit 2 ;;
esac
