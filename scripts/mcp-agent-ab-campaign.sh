#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
campaign_consent_override="${AI_FACTORY_RUN_CLOUD_CAMPAIGN:-}"
[ -f .env ] && set -a && source .env && set +a
if [ -n "$campaign_consent_override" ]; then
  AI_FACTORY_RUN_CLOUD_CAMPAIGN="$campaign_consent_override"
fi

manifest="${AI_FACTORY_AGENT_AB_MANIFEST:-resources/mcp/baselines/context-shadow-campaign-v1.json}"
api="${AI_FACTORY_AGENT_AB_API:-http://localhost:${ORCHESTRATOR_PORT:-8088}}"
variant="${1:-}"
output="${2:-}"
timeout="${AI_FACTORY_AGENT_AB_TASK_TIMEOUT_SECONDS:-3600}"

if [ "$variant" != "BASELINE" ] && [ "$variant" != "CANDIDATE" ]; then
  echo "Usage: $0 BASELINE|CANDIDATE output.jsonl" >&2
  exit 2
fi
if [ -z "$output" ] || [ "${AI_FACTORY_RUN_CLOUD_CAMPAIGN:-false}" != "true" ]; then
  echo "Output is required and AI_FACTORY_RUN_CLOUD_CAMPAIGN=true must be explicit." >&2
  exit 2
fi
jq -e '.version == "1" and (.tasks | length) == 20' "$manifest" >/dev/null
capabilities=$(curl -fsS --max-time 30 "$api/api/capabilities")
jq -e '.cloudAvailable == true and .mcpEnabled == true and .repositoryContextMcpAvailable == true' \
  >/dev/null <<<"$capabilities"
: > "$output"

while IFS= read -r task; do
  case_id=$(jq -r '.id' <<<"$task")
  repository=$(jq -r '.repository' <<<"$task")
  requirement=$(jq -r '.requirement' <<<"$task")
  payload=$(jq -cn --arg repositoryUrl "http://gitea:3000/${GITEA_ADMIN_USER:-aiadmin}/$repository.git" \
    --arg requirement "$requirement" '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement,llmMode:"CLOUD"}')
  state=$(curl -fsS --max-time 30 -X POST "$api/api/tasks" -H 'Content-Type: application/json' --data "$payload")
  task_id=$(jq -r '.id' <<<"$state")
  echo "$variant $case_id submitted as $task_id"
  started=$(date +%s)
  while true; do
    state=$(curl -fsS --max-time 30 "$api/api/tasks/$task_id")
    status=$(jq -r '.status' <<<"$state")
    case "$status" in WAITING_APPROVAL|FAILED|APPROVED|PR_CREATED) break ;; esac
    if [ $(( $(date +%s) - started )) -ge "$timeout" ]; then
      echo "$variant $case_id exceeded timeout" >&2
      exit 1
    fi
    sleep 5
  done
  jq -cn --arg case_id "$case_id" --arg variant "$variant" --arg task_id "$task_id" --arg status "$status" \
    --argjson m "$(jq '.evaluationMetrics' <<<"$state")" \
    --argjson security_failure "$(jq '((.error // "") | ascii_downcase | test("security|trivy|vulnerab"))' <<<"$state")" \
    '{case_id:$case_id,variant:$variant,task_id:$task_id,status:$status,
      first_patch_success:$m.first_patch_success,repairs:$m.repairs,tests_passed:$m.tests_passed,
      human_accepted:$m.human_accepted,tokens:$m.tokens,duration_millis:$m.duration_millis,
      cost_micros:$m.cost_micros,security_failure:$security_failure}' >> "$output"
done < <(jq -c '.tasks[]' "$manifest")
