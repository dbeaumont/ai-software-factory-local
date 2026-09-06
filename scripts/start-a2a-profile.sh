#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)

case "${1:-}" in
  role)
    role=${A2A_ROLE:?A2A_ROLE is required}
    allowed=false
    for candidate in "${roles[@]}"; do test "$candidate" != "$role" || allowed=true; done
    test "$allowed" = true || { echo "Unknown A2A role: $role" >&2; exit 2; }
    "${compose[@]}" --profile "a2a-$role" up -d "a2a-$role"
    A2A_ROLES="$role" ./scripts/a2a-local.sh smoke
    ;;
  full)
    services=()
    for role in "${roles[@]}"; do services+=("a2a-$role"); done
    "${compose[@]}" --profile a2a-full up -d "${services[@]}"
    ./scripts/a2a-local.sh smoke
    ;;
  *) echo "usage: start-a2a-profile.sh {role|full}" >&2; exit 2 ;;
esac
