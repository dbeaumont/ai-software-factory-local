#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

SONAR_PORT="$SONAR_PORT"
SONAR_LOGIN="$SONAR_ADMIN_LOGIN"
SONAR_PASSWORD="$SONAR_ADMIN_PASSWORD"

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

SONAR_URL="http://localhost:$SONAR_PORT"
echo "Waiting for SonarQube to become ready..."
until curl -fsS "$SONAR_URL/api/system/status" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; do sleep 2; done

if [ -n "$SONAR_TOKEN" ]; then
  if curl -fsS -u "$SONAR_TOKEN:" "$SONAR_URL/api/authentication/validate" 2>/dev/null | grep -Eq '"valid"[[:space:]]*:[[:space:]]*true'; then
    echo "SonarQube token already configured and valid."
    exit 0
  fi
  echo "Existing SonarQube token is invalid; generating a replacement."
fi

TOKEN_NAME="ai-factory-orchestrator-$(date +%s)"
TOKEN_RESPONSE=$(curl -sS -w '\n%{http_code}' -u "$SONAR_LOGIN:$SONAR_PASSWORD" \
  -X POST --data-urlencode "name=$TOKEN_NAME" \
  "$SONAR_URL/api/user_tokens/generate" 2>/dev/null || true)
TOKEN_HTTP_STATUS="${TOKEN_RESPONSE##*$'\n'}"
TOKEN_BODY="${TOKEN_RESPONSE%$'\n'*}"
TOKEN=$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("token", ""))' <<<"$TOKEN_BODY" 2>/dev/null || true)
if [ -n "$TOKEN" ]; then
  set_env "SONAR_TOKEN" "$TOKEN"
  echo "Generated SonarQube analysis token and saved it to .env"
elif [ "$TOKEN_HTTP_STATUS" = "401" ]; then
  echo "SonarQube authentication failed. SONAR_ADMIN_LOGIN and SONAR_ADMIN_PASSWORD in .env must match the existing SonarQube account."
  echo "The SonarQube volume keeps its password after .env changes. Update .env with the current password, then rerun make bootstrap."
  exit 1
else
  echo "Could not auto-generate a SonarQube token (HTTP $TOKEN_HTTP_STATUS)."
  exit 1
fi
