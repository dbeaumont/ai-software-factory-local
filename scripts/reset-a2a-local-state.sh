#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
expected=DELETE_A2A_LOCAL_STATE
test "${CONFIRM_A2A_RESET:-}" = "$expected" || {
  echo "Refusing A2A state deletion. Re-run with CONFIRM_A2A_RESET=$expected" >&2
  exit 2
}

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
services=(a2a-task-db)
for role in "${roles[@]}"; do services+=("a2a-$role"); done

"${compose[@]}" rm --stop --force "${services[@]}"
volume=$(docker volume ls --quiet \
  --filter label=com.docker.compose.project=ai-software-factory \
  --filter label=com.docker.compose.volume=a2a-task-db-data)
test -n "$volume" || { echo "No A2A task-state volume exists."; exit 0; }
test "$(printf '%s\n' "$volume" | wc -l | tr -d ' ')" = 1 || {
  echo "Refusing ambiguous A2A volume selection" >&2
  exit 2
}
docker volume rm "$volume"
echo "Removed local A2A task projection volume: $volume (not recoverable)."
