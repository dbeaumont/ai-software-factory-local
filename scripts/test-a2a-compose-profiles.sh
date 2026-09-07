#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)

minimal=$("${compose[@]}" --profile a2a-developer config --services)
grep -qx 'a2a-developer' <<< "$minimal"
if grep -Eq '^a2a-(supervisor|test-agent|security-agent)$' <<< "$minimal"; then
  echo "Minimal developer profile unexpectedly enables another A2A role" >&2
  exit 1
fi

full=$("${compose[@]}" --profile a2a-full config --services)
for role in "${roles[@]}"; do
  grep -qx "a2a-$role" <<< "$full" || { echo "Full A2A profile is missing $role" >&2; exit 1; }
done
grep -qx 'a2a-worker-activation' <<< "$full" || {
  echo "Full A2A profile is missing the Temporal worker activation gate" >&2
  exit 1
}

activation=$("${compose[@]}" --profile a2a-full config --format json | jq -r \
  '.services["a2a-worker-activation"].environment.EXPECTED_TASK_QUEUES')
for role in "${roles[@]}"; do
  grep -Eq "(^|,)a2a-agent-${role}-v1(,|$)" <<< "$activation" || {
    echo "A2A worker activation is missing the $role task queue" >&2
    exit 1
  }
done

echo "A2A Compose profiles verified: one isolated role and a gated, complete 14-role topology."
