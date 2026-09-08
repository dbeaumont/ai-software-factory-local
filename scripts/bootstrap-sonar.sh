#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a
COMPOSE=(docker compose --env-file .env -f infrastructure/compose.yaml)

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

valid_credentials() {
  curl -fsS -u "$SONAR_LOGIN:$1" "$SONAR_URL/api/authentication/validate" 2>/dev/null \
    | grep -Eq '"valid"[[:space:]]*:[[:space:]]*true'
}

# SonarQube stores the administrator password in PostgreSQL, independently of
# .env.  A stale local configuration must therefore be repaired in the backing
# store before a normal API-based rotation can be performed.  This only changes
# the password hash for the configured administrator; projects, analyses and
# tokens are preserved.
recover_administrator_password() {
  local existing_login
  case "$SONAR_LOGIN" in
    ''|*[!A-Za-z0-9_.-]*)
      echo "SONAR_ADMIN_LOGIN may contain only letters, digits, dot, underscore and hyphen for local recovery." >&2
      exit 1
      ;;
  esac

  existing_login=$("${COMPOSE[@]}" exec -T sonar-db \
    psql -U sonar -d sonar -qAt -v ON_ERROR_STOP=1 \
    -c "SELECT login FROM users WHERE login = '$SONAR_LOGIN';" 2>/dev/null) || {
      echo "Could not recover the SonarQube administrator password from its local database." >&2
      exit 1
    }
  if [ "$existing_login" != "$SONAR_LOGIN" ]; then
    echo "SonarQube administrator '$SONAR_LOGIN' was not found; refusing to modify the database." >&2
    exit 1
  fi

  # This is SonarQube's documented PBKDF2 record for the initial local
  # `admin` password. The next API call immediately replaces it with a fresh,
  # randomly generated password, so it is never retained in .env.
  "${COMPOSE[@]}" exec -T sonar-db \
    psql -U sonar -d sonar -q -v ON_ERROR_STOP=1 \
    -c "UPDATE users SET crypted_password = '100000\$t2h8AtNs1AlCHuLobDjHQTn9XppwTIx88UjqUm4s8RsfTuXQHSd/fpFexAnewwPsO6jGFQUv/24DnO55hY6Xew==', salt = 'k9x9eN127/3e/hf38iNiKwVfaVk=', hash_method = 'PBKDF2', reset_password = false, user_local = true WHERE login = '$SONAR_LOGIN';" \
    >/dev/null || {
      echo "Could not reset the SonarQube administrator password in its local database." >&2
      exit 1
    }

  SONAR_PASSWORD=admin
  echo "Recovered the SonarQube administrator password from the local database"
}

echo "Waiting for SonarQube to become ready..."
until curl -fsS "$SONAR_URL/api/system/status" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; do sleep 2; done

if ! valid_credentials "$SONAR_PASSWORD"; then
  echo "SonarQube administrator credentials in .env are stale; recovering the local administrator account..."
  recover_administrator_password
  if ! valid_credentials "$SONAR_PASSWORD"; then
    echo "SonarQube administrator recovery did not produce valid credentials." >&2
    exit 1
  fi
fi

NEXT_SONAR_PASSWORD="Aa1!$(openssl rand -hex 30)"
PASSWORD_RESPONSE=$(curl -sS -w '\n%{http_code}' -u "$SONAR_LOGIN:$SONAR_PASSWORD" \
  -X POST --data-urlencode "login=$SONAR_LOGIN" \
  --data-urlencode "previousPassword=$SONAR_PASSWORD" \
  --data-urlencode "password=$NEXT_SONAR_PASSWORD" \
  "$SONAR_URL/api/users/change_password" 2>/dev/null || true)
PASSWORD_HTTP_STATUS="${PASSWORD_RESPONSE##*$'\n'}"
if [ "$PASSWORD_HTTP_STATUS" != "204" ]; then
  echo "Could not rotate the SonarQube administrator password (HTTP $PASSWORD_HTTP_STATUS)."
  exit 1
fi
set_env "SONAR_ADMIN_PASSWORD" "$NEXT_SONAR_PASSWORD"
SONAR_PASSWORD="$NEXT_SONAR_PASSWORD"
chmod 600 .env
echo "Rotated SonarQube administrator password and saved it to .env"

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
  echo "SonarQube authentication failed after administrator password rotation."
  exit 1
else
  echo "Could not auto-generate a SonarQube token (HTTP $TOKEN_HTTP_STATUS)."
  exit 1
fi
