#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a
PORT="${ORCHESTRATOR_PORT:-8088}"
USER="${GITEA_ADMIN_USER:-aiadmin}"
HTTP_PORT="${GITEA_HTTP_PORT:-3000}"
REPO="http://gitea:3000/$USER/customer-api.git"

curl -fsS -X POST "http://localhost:$PORT/api/tasks" \
  -H 'Content-Type: application/json' \
  -d "{\"repositoryUrl\":\"$REPO\",\"baseBranch\":\"main\",\"requirement\":\"Add GET /customers/{id}. Return HTTP 404 when the customer does not exist. Add automated tests.\",\"dryRun\":true}" \
  | tee /tmp/ai-factory-task.json

echo
echo "Task submitted in dry-run mode."
echo "List tasks: curl http://localhost:$PORT/api/tasks | jq"
echo "For real patch/test execution, submit the same payload with dryRun=false."
