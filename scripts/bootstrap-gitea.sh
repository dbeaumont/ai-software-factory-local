#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a
USER="${GITEA_ADMIN_USER:-aiadmin}"
PASS="${GITEA_ADMIN_PASSWORD:-ChangeMe123!}"
EMAIL="${GITEA_ADMIN_EMAIL:-aiadmin@example.local}"
HTTP_PORT="${GITEA_HTTP_PORT:-3000}"

until docker compose exec -T --user git gitea gitea admin user list >/dev/null 2>&1; do sleep 2; done
if ! docker compose exec -T --user git gitea gitea admin user list | grep -q "${USER}"; then
  docker compose exec -T --user git gitea gitea admin user create --username "$USER" --password "$PASS" --email "$EMAIL" --admin --must-change-password=false
fi

if ! curl -fsS -u "$USER:$PASS" "http://localhost:$HTTP_PORT/api/v1/repos/$USER/customer-api" >/dev/null 2>&1; then
  curl -fsS -u "$USER:$PASS" -H 'Content-Type: application/json' \
    -d '{"name":"customer-api","private":false,"auto_init":false,"default_branch":"main"}' \
    "http://localhost:$HTTP_PORT/api/v1/user/repos" >/dev/null
fi

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
  TOKEN_JSON=$(curl -fsS -u "$USER:$PASS" -H 'Content-Type: application/json' \
    -d '{"name":"ai-factory-orchestrator","scopes":["write:repository","write:issue"]}' \
    "http://localhost:$HTTP_PORT/api/v1/users/$USER/tokens" || true)
  TOKEN=$(printf '%s' "$TOKEN_JSON" | sed -n 's/.*"sha1":"\([^"]*\)".*/\1/p')
  if [ -n "$TOKEN" ]; then
    if grep -q '^GITEA_TOKEN=' .env; then
      python3 - "$TOKEN" <<'PY2'
from pathlib import Path
import sys
p=Path('.env')
t=p.read_text()
lines=[]
for line in t.splitlines():
    lines.append('GITEA_TOKEN='+sys.argv[1] if line.startswith('GITEA_TOKEN=') else line)
p.write_text('\n'.join(lines)+'\n')
PY2
    else
      printf '\nGITEA_TOKEN=%s\n' "$TOKEN" >> .env
    fi
    echo "Generated Gitea token and saved it to .env"
    docker compose up -d --force-recreate orchestrator >/dev/null
  else
    echo "Could not auto-generate a Gitea token. Create one in Settings -> Applications and set GITEA_TOKEN in .env."
  fi
fi

echo "Demo repository ready: http://localhost:$HTTP_PORT/$USER/customer-api"
