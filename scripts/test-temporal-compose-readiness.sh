#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}
deployment=${AI_FACTORY_TEMPORAL_DEPLOYMENT_NAME:-ai-factory-orchestrator}
build_id=${AI_FACTORY_TEMPORAL_BUILD_ID:-0.1.0}
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
ui_port=${TEMPORAL_UI_PORT:-8233}

for service in temporal temporal-ui orchestrator; do
  state=$("${compose[@]}" ps --format json "$service" | jq -sr '.[0].State // empty')
  [ "$state" = "running" ] || { echo "Compose service is not running: $service" >&2; exit 1; }
done

curl -fsS "http://127.0.0.1:${orchestrator_port}/actuator/health/readiness" \
  | jq -e '.status == "UP"' >/dev/null
curl -fsS -o /dev/null "http://127.0.0.1:${ui_port}/"

temporal_cli=("${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-namespace)
"${temporal_cli[@]}" operator namespace describe --address temporal:7233 \
  --namespace "$namespace" >/dev/null

attributes=$("${temporal_cli[@]}" operator search-attribute list --address temporal:7233 \
  --namespace "$namespace" --output json)
for attribute in AiFactoryTaskId AiFactoryAttemptId AiFactoryRepositoryId AiFactoryExecutionMode; do
  printf '%s\n' "$attributes" | jq -e --arg attribute "$attribute" \
    '.. | objects | select(has($attribute))' >/dev/null
done

deployment_state=$("${temporal_cli[@]}" worker deployment describe --address temporal:7233 \
  --namespace "$namespace" --name "$deployment" --output json)
printf '%s\n' "$deployment_state" | jq -e --arg build "$build_id" \
  '.routingConfig.currentVersionBuildID == $build' >/dev/null

queues=(
  "${AI_FACTORY_TEMPORAL_WORKFLOW_TASK_QUEUE:-ai-factory-workflows}:workflow"
  "${AI_FACTORY_TEMPORAL_CONTEXT_TASK_QUEUE:-ai-factory-context}:activity"
  "${AI_FACTORY_TEMPORAL_LLM_TASK_QUEUE:-ai-factory-llm}:activity"
  "${AI_FACTORY_TEMPORAL_SANDBOX_TASK_QUEUE:-ai-factory-sandbox}:activity"
  "${AI_FACTORY_TEMPORAL_ASSURANCE_TASK_QUEUE:-ai-factory-assurance}:activity"
  "${AI_FACTORY_TEMPORAL_EVIDENCE_TASK_QUEUE:-ai-factory-evidence}:activity"
  "${AI_FACTORY_TEMPORAL_SCM_TASK_QUEUE:-ai-factory-scm}:activity"
)
for queue_spec in "${queues[@]}"; do
  queue=${queue_spec%%:*}
  task_type=${queue_spec##*:}
  pollers=$("${temporal_cli[@]}" task-queue describe --legacy-mode --address temporal:7233 \
    --namespace "$namespace" --task-queue "$queue" --task-queue-type-legacy "$task_type")
  printf '%s\n' "$pollers" | grep -Eq '^[[:space:]]+[0-9]+@[^[:space:]]+' || {
    echo "No $task_type poller is visible for task queue '$queue'." >&2
    exit 1
  }
done

echo "Temporal Compose readiness verified: namespace, UI, application and seven pollers are active."
