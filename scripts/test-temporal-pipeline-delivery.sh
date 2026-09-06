#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
orchestrator_port=${ORCHESTRATOR_PORT:-8088}
database_name=${ORCHESTRATOR_DB_NAME:-ai_factory}
database_user=${ORCHESTRATOR_DB_USER:-ai_factory}
gitea_port=${GITEA_PORT:-3000}
gitea_owner=${GITEA_ADMIN_USER:-aiadmin}
gitea_repository=${TEMPORAL_TEST_REPOSITORY:-customer-api}
repository_url=${TEMPORAL_TEST_REPOSITORY_URL:-http://gitea:3000/${gitea_owner}/${gitea_repository}.git}
requirement=${TEMPORAL_DELIVERY_TEST_REQUIREMENT:-Temporal full delivery verification: add one concise comment to the customer not-found test without changing behavior.}
timeout=${TEMPORAL_DELIVERY_TEST_TIMEOUT_SECONDS:-1200}
qualification_attempts=${TEMPORAL_DELIVERY_TEST_ATTEMPTS:-3}

: "${GITEA_TOKEN:?GITEA_TOKEN is required for the end-to-end delivery assertion}"

response_file=$(mktemp)
task_file=$(mktemp)
pulls_file=$(mktemp)
trap 'rm -f "$response_file" "$task_file" "$pulls_file"' EXIT

gitea_pulls() {
  curl -fsS --max-time 30 \
    -H "Authorization: token ${GITEA_TOKEN}" \
    "http://127.0.0.1:${gitea_port}/api/v1/repos/${gitea_owner}/${gitea_repository}/pulls?state=all&limit=50"
}

task_status() {
  "${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT status FROM tasks WHERE task_id = '${task_id}'" | tr -d '[:space:]'
}

wait_for_status() {
  expected=$1
  deadline=$(( $(date +%s) + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    status=$(task_status)
    [ "$status" = "$expected" ] && return 0
    case "$status" in
      GATE_REJECTED)
        [ "$expected" = WAITING_APPROVAL ] && return 10
        echo "Task $task_id was rejected while waiting for $expected." >&2
        return 1
        ;;
      FAILED|CANCELLED)
        echo "Task $task_id reached $status while waiting for $expected." >&2
        return 1
        ;;
    esac
    sleep 1
  done
  echo "Task $task_id did not reach $expected within ${timeout}s." >&2
  return 1
}

gitea_pulls >"$pulls_file"
pull_count_before=$(jq 'length' "$pulls_file")

waiting_reached=false
for qualification_attempt in $(seq 1 "$qualification_attempts"); do
  payload=$(jq -cn --arg repositoryUrl "$repository_url" --arg requirement "$requirement Attempt: $qualification_attempt." \
    '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement}')
  curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
    -H 'Content-Type: application/json' --data "$payload" >"$response_file"
  task_id=$(jq -er '.id' "$response_file")
  attempt_id=$(jq -er '.workflowAttemptId' "$response_file")
  run_id=$(jq -er '.workflowRunId' "$response_file")
  if wait_for_status WAITING_APPROVAL; then
    waiting_reached=true
    break
  else
    result=$?
    if [ "$result" -ne 10 ] || [ "$qualification_attempt" -eq "$qualification_attempts" ]; then
      exit "$result"
    fi
    echo "Retrying full pipeline after functional gate rejection ($qualification_attempt/$qualification_attempts)..."
  fi
done
[ "$waiting_reached" = true ] || exit 1

curl -fsS --max-time 30 "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}" >"$task_file"
jq -e '
  .status == "WAITING_APPROVAL"
  and (.patch | type == "string" and length > 0)
  and ([.artifacts[] | select(.artifactId == "patch" and .status == "COMPLETE")] | length == 1)
  and ([.artifacts[] | select(.artifactId == "tests" and .status == "COMPLETE")] | length == 1)
  and ([.artifacts[] | select(.artifactId == "quality" and .status == "COMPLETE")] | length == 1)
  and ([.artifacts[] | select(.artifactId == "security" and .status == "COMPLETE")] | length == 1)
  and ([.artifacts[] | select(.artifactId == "sbom" and .status == "COMPLETE")] | length == 1)
  and ([.artifacts[] | select(.artifactId == "review" and .status == "COMPLETE")] | length == 1)
  and .assuranceResults.tests.verdict == "PASSED"
  and .assuranceResults.quality.verdict == "PASSED"
  and .assuranceResults.security.verdict == "PASSED"
  and .assuranceResults.sbom.status == "COMPLETE"
  and (.review | fromjson | .decision == "ACCEPT")
  and .pendingEffect.tool == "scm.create_draft_pull_request"
  and .pendingEffect.policyDecision == "ALLOW"
  and .pendingEffect.confirmationRequired == true
' "$task_file" >/dev/null

manifest_id=$(jq -er '.pendingEffect.manifestId' "$task_file")
manifest_digest=$(jq -er '.pendingEffect.manifestDigest' "$task_file")
approval_payload=$(jq -cn --arg manifestId "$manifest_id" --arg manifestDigest "$manifest_digest" \
  '{manifestId:$manifestId,manifestDigest:$manifestDigest}')
curl -fsS --max-time 30 -X POST \
  "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/approve-manifest" \
  -H 'Content-Type: application/json' --data "$approval_payload" >/dev/null
wait_for_status PR_CREATED

curl -fsS --max-time 30 "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}" >"$task_file"
pull_request_url=$(jq -er '.pullRequestUrl' "$task_file")
[ "$(jq -r '.status' "$task_file")" = PR_CREATED ] || exit 1
[ "$(jq -r '.workflowRunId' "$task_file")" = "$run_id" ] || {
  echo "Temporal Run ID changed during delivery." >&2
  exit 1
}

gitea_pulls >"$pulls_file"
pull_count_after=$(jq 'length' "$pulls_file")
[ "$pull_count_after" -eq $((pull_count_before + 1)) ] || {
  echo "Expected exactly one new Gitea PR; count changed from $pull_count_before to $pull_count_after." >&2
  exit 1
}
[ "$(jq --arg url "$pull_request_url" '[.[] | select(.html_url == $url)] | length' "$pulls_file")" = 1 ] || {
  echo "The projected pull request is not unique in Gitea." >&2
  exit 1
}

curl -fsS --max-time 30 -X POST \
  "http://127.0.0.1:${orchestrator_port}/api/tasks/${task_id}/approve-manifest" \
  -H 'Content-Type: application/json' --data "$approval_payload" >/dev/null
sleep 2
gitea_pulls >"$pulls_file"
[ "$(jq 'length' "$pulls_file")" = "$pull_count_after" ] || {
  echo "Repeating approval created a duplicate Gitea PR." >&2
  exit 1
}

echo "Full Temporal delivery verified: task=$task_id run=$run_id manifest=$manifest_id pr=$pull_request_url unique_pr=1"
