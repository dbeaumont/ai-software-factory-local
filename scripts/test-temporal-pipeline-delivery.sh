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
cutover_smoke=${TEMPORAL_CUTOVER_SMOKE:-false}
workspace_volume=${AI_FACTORY_WORKSPACE_VOLUME:-factory-workspace}

: "${GITEA_TOKEN:?GITEA_TOKEN is required for the end-to-end delivery assertion}"

case "$cutover_smoke" in
  true|false) ;;
  *) echo "TEMPORAL_CUTOVER_SMOKE must be true or false" >&2; exit 2 ;;
esac

response_file=$(mktemp)
task_file=$(mktemp)
pulls_file=$(mktemp)
admissions_temporarily_open=false
cleanup() {
  if [ "$admissions_temporarily_open" = true ]; then
    ./scripts/set-admissions.sh close >/dev/null || true
  fi
  rm -f "$response_file" "$task_file" "$pulls_file"
}
trap cleanup EXIT

cleanup_workspace() {
  disposable_task_id=$1
  [[ "$disposable_task_id" =~ ^[0-9a-f]{8}$ ]] || {
    echo "Refusing to clean an invalid task workspace: $disposable_task_id" >&2
    return 1
  }
  docker run --rm --network none -v "${workspace_volume}:/workspace" \
    busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
    sh -eu -c 'target="/workspace/tasks/$1"; rm -rf -- "$target"; [ ! -e "$target" ]' _ "$disposable_task_id"
}

admit_cutover_smoke() {
  admission_state=$("${compose[@]}" exec -T orchestrator-db psql -U "$database_user" -d "$database_name" -Atc \
    "SELECT admissions_open FROM factory_admission_control WHERE control_key = 'global'" | tr -d '[:space:]')
  [ "$admission_state" = f ] || {
    echo "Cutover smoke requires globally closed admissions" >&2
    return 1
  }
  admissions_temporarily_open=true
  ./scripts/set-admissions.sh open >/dev/null
  curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
    -H 'Content-Type: application/json' --data "$payload" >"$response_file"
  ./scripts/set-admissions.sh close >/dev/null
  admissions_temporarily_open=false
}

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
    '{repositoryUrl:$repositoryUrl,baseBranch:"main",requirement:$requirement,routingFacts:{qualification:"QUALIFIED",risk:"R1",modules:1,domains:1,estimatedFiles:2,independentCodeScopes:1,impacts:[],materialDecisionOpen:false,inputsComplete:true,contradictory:false,budgetAvailable:true}}')
  if [ "$cutover_smoke" = true ]; then
    admit_cutover_smoke
  else
    curl -fsS --max-time 30 -X POST "http://127.0.0.1:${orchestrator_port}/api/tasks" \
      -H 'Content-Type: application/json' --data "$payload" >"$response_file"
  fi
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
    [ "$cutover_smoke" = false ] || cleanup_workspace "$task_id"
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

cleanup_result=retained
if [ "$cutover_smoke" = true ]; then
  pull_number=$(jq -er --arg url "$pull_request_url" '.[] | select(.html_url == $url) | .number' "$pulls_file")
  head_branch=$(jq -er --arg url "$pull_request_url" '.[] | select(.html_url == $url) | .head.ref' "$pulls_file")
  curl -fsS --max-time 30 -X PATCH \
    -H "Authorization: token ${GITEA_TOKEN}" -H 'Content-Type: application/json' \
    --data '{"state":"closed"}' \
    "http://127.0.0.1:${gitea_port}/api/v1/repos/${gitea_owner}/${gitea_repository}/pulls/${pull_number}" \
    | jq -e '.state == "closed"' >/dev/null
  encoded_branch=$(jq -rn --arg branch "$head_branch" '$branch | @uri')
  curl -fsS --max-time 30 -X DELETE -H "Authorization: token ${GITEA_TOKEN}" \
    "http://127.0.0.1:${gitea_port}/api/v1/repos/${gitea_owner}/${gitea_repository}/branches/${encoded_branch}"
  cleanup_workspace "$task_id"
  cleanup_result="pr_closed,branch_deleted,workspace_deleted,evidence_retained"
fi

echo "Full Temporal delivery verified: task=$task_id run=$run_id manifest=$manifest_id pr=$pull_request_url unique_pr=1 cleanup=$cleanup_result"
