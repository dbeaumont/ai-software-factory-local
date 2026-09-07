#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}
deployment=${AI_FACTORY_A2A_TEMPORAL_DEPLOYMENT:-ai-factory-a2a-agents}
build_id=${AI_FACTORY_A2A_TEMPORAL_BUILD_ID:-0.1.0}
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
temporal_cli=("${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-namespace)

echo "Checking A2A Temporal deployment '$deployment' at Build ID '$build_id'..."
deployment_state=$("${temporal_cli[@]}" worker deployment describe --address temporal:7233 \
  --namespace "$namespace" --name "$deployment" --output json)
printf '%s\n' "$deployment_state" | jq -e --arg build "$build_id" \
  '.routingConfig.currentVersionBuildID == $build' >/dev/null

for role in "${roles[@]}"; do
  queue="a2a-agent-${role}-v1"
  echo "Checking workflow and activity pollers for $queue..."
  for task_type in workflow activity; do
    pollers=$("${temporal_cli[@]}" task-queue describe --legacy-mode --address temporal:7233 \
      --namespace "$namespace" --task-queue "$queue" --task-queue-type-legacy "$task_type")
    printf '%s\n' "$pollers" | grep -Eq '^[[:space:]]+[0-9]+@[^[:space:]]+' || {
      echo "No $task_type poller is visible for A2A task queue '$queue'." >&2
      exit 1
    }
  done
done

echo "A2A Temporal readiness verified: current Build ID, 14 task queues and 28 poller sets are active."
