#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
campaign_consent_override="${AI_FACTORY_RUN_CLOUD_CAMPAIGN:-}"
[ -f .env ] && set -a && source .env && set +a
if [ -n "$campaign_consent_override" ]; then
  AI_FACTORY_RUN_CLOUD_CAMPAIGN="$campaign_consent_override"
fi

manifest="${AI_FACTORY_SHADOW_CAMPAIGN_MANIFEST:-resources/mcp/baselines/context-shadow-campaign-v1.json}"
mode="${1:---dry-run}"
port="${ORCHESTRATOR_PORT:-8088}"
api="${AI_FACTORY_SHADOW_CAMPAIGN_API:-http://localhost:$port}"
task_timeout="${AI_FACTORY_SHADOW_TASK_TIMEOUT_SECONDS:-3600}"
poll_interval="${AI_FACTORY_SHADOW_POLL_INTERVAL_SECONDS:-5}"
user="${GITEA_ADMIN_USER:-aiadmin}"

if [ "$mode" != "--dry-run" ] && [ "$mode" != "--execute" ]; then
  echo "Usage: $0 [--dry-run|--execute]" >&2
  exit 2
fi
if ! command -v jq >/dev/null; then
  echo "jq is required to validate and execute the campaign" >&2
  exit 2
fi
if ! jq -e '
    .version == "1"
    and (.tasks | length) >= 20
    and (.tasks | length) <= 100
    and ([.tasks[].id] | length == (unique | length))
    and ([.tasks[].ecosystem] | unique | sort) == ["GRADLE", "MAVEN", "NPM"]
    and all(.tasks[];
      (.id | test("^CTX-[0-9]{3}$"))
      and (.repository | IN("customer-api", "inventory-gradle", "checkout-node"))
      and (.category | IN("SIMPLE", "VALIDATION", "MULTI_FILE", "RULES", "NEGATIVE"))
      and ((.requirement | length) >= 20 and (.requirement | length) <= 4000))
  ' "$manifest" >/dev/null; then
  echo "Invalid MCP context shadow campaign manifest: $manifest" >&2
  exit 2
fi

task_count=$(jq '.tasks | length' "$manifest")
echo "Campaign manifest: $manifest"
echo "Tasks: $task_count"
jq -r '.tasks | group_by(.ecosystem)[] | "  \(.[0].ecosystem): \(length)"' "$manifest"
jq -r '.tasks | group_by(.category)[] | "  \(.[0].category): \(length)"' "$manifest"

if [ "$mode" = "--dry-run" ]; then
  echo "Dry run complete. Set AI_FACTORY_RUN_CLOUD_CAMPAIGN=true and use --execute to submit cloud tasks."
  exit 0
fi
if [ "${AI_FACTORY_RUN_CLOUD_CAMPAIGN:-false}" != "true" ]; then
  echo "Refusing to spend cloud capacity: set AI_FACTORY_RUN_CLOUD_CAMPAIGN=true explicitly." >&2
  exit 2
fi
if ! [[ "$task_timeout" =~ ^[0-9]+$ ]] || [ "$task_timeout" -lt 60 ]; then
  echo "AI_FACTORY_SHADOW_TASK_TIMEOUT_SECONDS must be an integer of at least 60" >&2
  exit 2
fi
if ! [[ "$poll_interval" =~ ^[0-9]+$ ]] || [ "$poll_interval" -lt 1 ]; then
  echo "AI_FACTORY_SHADOW_POLL_INTERVAL_SECONDS must be a positive integer" >&2
  exit 2
fi

capabilities=$(curl -fsS --max-time 30 "$api/api/capabilities")
if ! jq -e '.cloudAvailable == true and .mcpEnabled == true and .repositoryContextMcpAvailable == true' \
  >/dev/null <<<"$capabilities"; then
  echo "Cloud or repository-context-mcp is unavailable; campaign not started." >&2
  exit 1
fi

timestamp=$(date -u +%Y%m%d-%H%M%S)
results="docs/mcp/baselines/MCP-context-shadow-campaign-$timestamp.jsonl"
report="docs/mcp/baselines/MCP-shadow-$timestamp.md"
: > "$results"

while IFS= read -r task; do
  case_id=$(jq -r '.id' <<<"$task")
  repository=$(jq -r '.repository' <<<"$task")
  ecosystem=$(jq -r '.ecosystem' <<<"$task")
  category=$(jq -r '.category' <<<"$task")
  requirement=$(jq -r '.requirement' <<<"$task")
  payload=$(jq -cn \
    --arg repositoryUrl "http://gitea:3000/$user/$repository.git" \
    --arg requirement "$requirement" \
    '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement,llmMode:"CLOUD"}')
  response=$(curl -fsS --max-time 30 -X POST "$api/api/tasks" \
    -H 'Content-Type: application/json' --data "$payload")
  task_id=$(jq -r '.id' <<<"$response")
  if [ -z "$task_id" ] || [ "$task_id" = "null" ]; then
    echo "Task submission returned no id for $case_id" >&2
    exit 1
  fi
  echo "$case_id submitted as $task_id ($ecosystem/$category)"

  started=$(date +%s)
  while true; do
    state=$(curl -fsS --max-time 30 "$api/api/tasks/$task_id")
    status=$(jq -r '.status' <<<"$state")
    case "$status" in
      WAITING_APPROVAL|FAILED|APPROVED|PR_CREATED) break ;;
    esac
    if [ $(( $(date +%s) - started )) -ge "$task_timeout" ]; then
      echo "Campaign stopped: $case_id exceeded the monitoring timeout; task was not cancelled." >&2
      exit 1
    fi
    sleep "$poll_interval"
  done
  jq -cn \
    --arg case_id "$case_id" --arg task_id "$task_id" --arg repository "$repository" \
    --arg ecosystem "$ecosystem" --arg category "$category" --arg status "$status" \
    --argjson plan_present "$(jq '(.plan // "") | length > 0' <<<"$state")" \
    --argjson plan_chars "$(jq '(.plan // "") | length' <<<"$state")" \
    --argjson error_present "$(jq '(.error // "") | length > 0' <<<"$state")" \
    '{case_id:$case_id,task_id:$task_id,repository:$repository,ecosystem:$ecosystem,category:$category,status:$status,plan_present:$plan_present,plan_chars:$plan_chars,error_present:$error_present}' \
    >> "$results"
done < <(jq -c '.tasks[]' "$manifest")

AI_FACTORY_REPORT_ORCHESTRATOR_URL="$api" ./scripts/mcp-shadow-report.sh "$report"
echo "Campaign results: $results"
echo "Shadow metrics report: $report"
