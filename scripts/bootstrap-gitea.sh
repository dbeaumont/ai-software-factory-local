#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a
USER="${GITEA_ADMIN_USER:-aiadmin}"
PASS="${GITEA_ADMIN_PASSWORD:-ChangeMe123!}"
EMAIL="${GITEA_ADMIN_EMAIL:-aiadmin@example.local}"
REVIEWER_USER="${GITEA_REVIEWER_USER:-reviewer}"
REVIEWER_PASS="${GITEA_REVIEWER_PASSWORD:-ChangeMe123!}"
REVIEWER_EMAIL="${GITEA_REVIEWER_EMAIL:-reviewer@example.local}"
HTTP_PORT="${GITEA_HTTP_PORT:-3000}"

set_env() {
  local key="$1"
  local value="$2"
  python3 - "$key" "$value" <<'PY'
from pathlib import Path
import sys

path = Path('.env')
key, value = sys.argv[1:]
lines = path.read_text().splitlines() if path.exists() else []
updated = False
for index, line in enumerate(lines):
    if line.startswith(f"{key}="):
        lines[index] = f"{key}={value}"
        updated = True
if not updated:
    lines.append(f"{key}={value}")
path.write_text("\n".join(lines) + "\n")
PY
}

until docker compose exec -T --user git gitea gitea admin user list >/dev/null 2>&1; do sleep 2; done
if ! docker compose exec -T --user git gitea gitea admin user list | grep -q "${USER}"; then
  docker compose exec -T --user git gitea gitea admin user create --username "$USER" --password "$PASS" --email "$EMAIL" --admin --must-change-password=false
fi
if ! docker compose exec -T --user git gitea gitea admin user list | grep -q "${REVIEWER_USER}"; then
  docker compose exec -T --user git gitea gitea admin user create --username "$REVIEWER_USER" --password "$REVIEWER_PASS" --email "$REVIEWER_EMAIL" --must-change-password=false
fi

if ! curl -fsS -u "$USER:$PASS" "http://localhost:$HTTP_PORT/api/v1/repos/$USER/customer-api" >/dev/null 2>&1; then
  curl -fsS -u "$USER:$PASS" -H 'Content-Type: application/json' \
    -d '{"name":"customer-api","private":false,"auto_init":false,"default_branch":"main"}' \
    "http://localhost:$HTTP_PORT/api/v1/user/repos" >/dev/null
fi

# The PR author cannot approve their own PR, so grant a distinct reviewer write access.
curl -fsS -u "$USER:$PASS" -X PUT -H 'Content-Type: application/json' \
  -d '{"permission":"write"}' \
  "http://localhost:$HTTP_PORT/api/v1/repos/$USER/customer-api/collaborators/$REVIEWER_USER" >/dev/null

TMP=$(mktemp -d)
cp -R sample-repo/. "$TMP/"
(
  cd "$TMP"
  git init -b main >/dev/null
  git add .
  git -c user.name='Demo' -c user.email='demo@example.local' commit -m 'Initial demo app' >/dev/null
  git remote add origin "http://$USER:$PASS@localhost:$HTTP_PORT/$USER/customer-api.git"
  git push -u origin main --force >/dev/null
)
rm -rf "$TMP"

if [ -z "${GITEA_TOKEN:-}" ]; then
  TOKEN_NAME="ai-factory-orchestrator-$(date +%s)"
  TOKEN=$(docker compose exec -T --user git gitea \
    gitea admin user generate-access-token \
    --username "$USER" \
    --token-name "$TOKEN_NAME" \
    --scopes "write:repository,write:issue" \
    --raw 2>/dev/null || true)
  if [ -n "$TOKEN" ]; then
    set_env "GITEA_TOKEN" "$TOKEN"
    echo "Generated Gitea token and saved it to .env"
  else
    echo "Could not auto-generate a Gitea token. Create one in Settings -> Applications and set GITEA_TOKEN in .env."
  fi
fi

echo "Demo repository ready: http://localhost:$HTTP_PORT/$USER/customer-api"
