#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

SONAR_PORT="${SONAR_PORT:-9000}"
SONAR_LOGIN="${SONAR_ADMIN_LOGIN:-admin}"
SONAR_PASSWORD="${SONAR_ADMIN_PASSWORD:-admin}"

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

if [ -n "${SONAR_TOKEN:-}" ]; then
  echo "SonarQube token already configured."
  exit 0
fi

SONAR_URL="http://localhost:$SONAR_PORT"
echo "Waiting for SonarQube to become ready..."
until curl -fsS "$SONAR_URL/api/system/status" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; do sleep 2; done

TOKEN_NAME="ai-factory-orchestrator-$(date +%s)"
TOKEN_RESPONSE=$(curl -fsS -u "$SONAR_LOGIN:$SONAR_PASSWORD" \
  -X POST --data-urlencode "name=$TOKEN_NAME" \
  "$SONAR_URL/api/user_tokens/generate" 2>/dev/null || true)
TOKEN=$(python3 -c 'import json, sys; print(json.load(sys.stdin).get("token", ""))' <<<"$TOKEN_RESPONSE" 2>/dev/null || true)
if [ -n "$TOKEN" ]; then
  set_env "SONAR_TOKEN" "$TOKEN"
  echo "Generated SonarQube analysis token and saved it to .env"
else
  echo "Could not auto-generate a SonarQube token. Check SONAR_ADMIN_LOGIN and SONAR_ADMIN_PASSWORD in .env."
  exit 1
fi
