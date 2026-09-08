#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

source scripts/load-dotenv.sh
load_dotenv .env

SIGNOZ_BASE_URL=${SIGNOZ_BASE_URL:-http://127.0.0.1:${SIGNOZ_PORT:-3301}}
SIGNOZ_ROOT_EMAIL=${SIGNOZ_ROOT_EMAIL:-admin@ai-factory.local}
: "${SIGNOZ_ROOT_PASSWORD:?SIGNOZ_ROOT_PASSWORD must be initialized by make init}"

set_env() {
  local key="$1" value="$2"
  python3 - "$key" "$value" <<'PY'
from pathlib import Path
import sys

path = Path('.env')
key, value = sys.argv[1:]
lines = path.read_text().splitlines() if path.exists() else []
for index, line in enumerate(lines):
    if line.startswith(f"{key}="):
        lines[index] = f"{key}={value}"
        break
else:
    lines.append(f"{key}={value}")
path.write_text("\n".join(lines) + "\n")
PY
}

for attempt in {1..90}; do
  if curl -fsS "$SIGNOZ_BASE_URL/api/v1/health" >/dev/null; then
    break
  fi
  if [ "$attempt" -eq 90 ]; then
    echo "SigNoz did not become healthy at $SIGNOZ_BASE_URL" >&2
    exit 1
  fi
  sleep 1
done

# The health endpoint becomes ready before the root user/session routes on a fresh database.
# Retry the complete session handshake instead of making the one-shot bootstrap fail on a transient 404.
token=
for attempt in {1..90}; do
  session_context=$(curl -fsS --get "$SIGNOZ_BASE_URL/api/v2/sessions/context" \
    --data-urlencode "email=$SIGNOZ_ROOT_EMAIL" \
    --data-urlencode "ref=$SIGNOZ_BASE_URL" 2>/dev/null || true)
  org_id=$(printf '%s' "$session_context" | jq -er '.data.orgs[0].id' 2>/dev/null || true)
  if [ -n "$org_id" ]; then
    login_payload=$(jq -nc --arg email "$SIGNOZ_ROOT_EMAIL" --arg password "$SIGNOZ_ROOT_PASSWORD" \
      --arg orgId "$org_id" '{email:$email,password:$password,orgId:$orgId}')
    session=$(curl -fsS -X POST "$SIGNOZ_BASE_URL/api/v2/sessions/email_password" \
      -H 'Content-Type: application/json' --data "$login_payload" 2>/dev/null || true)
    token=$(printf '%s' "$session" | jq -er '.data.accessToken' 2>/dev/null || true)
  fi
  [ -n "$token" ] && break
  if [ "$attempt" -eq 90 ]; then
    echo "SigNoz root session did not become ready at $SIGNOZ_BASE_URL" >&2
    exit 1
  fi
  sleep 1
done

cleanup() {
  curl -fsS -X DELETE "$SIGNOZ_BASE_URL/api/v2/sessions" -H "Authorization: Bearer $token" >/dev/null || true
}
trap cleanup EXIT

auth=(-H "Authorization: Bearer $token")

# The Compose one-shot job has a read-only filesystem and receives credentials
# through its environment; it cannot safely propagate a rotated password back
# to the host. Password rotation is therefore attempted only by the host-side
# `make bootstrap-signoz` invocation, where .env is present and writable.
if [ -f .env ]; then
  NEXT_SIGNOZ_PASSWORD="Aa1!$(openssl rand -hex 30)"
  password_payload=$(jq -nc --arg old "$SIGNOZ_ROOT_PASSWORD" --arg new "$NEXT_SIGNOZ_PASSWORD" \
    '{oldPassword:$old,newPassword:$new}')
  password_status=$(curl -sS -o /tmp/signoz-password-rotation.json -w '%{http_code}' \
    -X PUT "$SIGNOZ_BASE_URL/api/v2/users/me/factor_password" "${auth[@]}" \
    -H 'Content-Type: application/json' --data "$password_payload")
  if [ "$password_status" = "204" ]; then
    set_env "SIGNOZ_ROOT_PASSWORD" "$NEXT_SIGNOZ_PASSWORD"
    SIGNOZ_ROOT_PASSWORD="$NEXT_SIGNOZ_PASSWORD"
    chmod 600 .env
    login_payload=$(jq -nc --arg email "$SIGNOZ_ROOT_EMAIL" --arg password "$SIGNOZ_ROOT_PASSWORD" \
      --arg orgId "$org_id" '{email:$email,password:$password,orgId:$orgId}')
    session=$(curl -fsS -X POST "$SIGNOZ_BASE_URL/api/v2/sessions/email_password" \
      -H 'Content-Type: application/json' --data "$login_payload")
    token=$(printf '%s' "$session" | jq -er '.data.accessToken')
    auth=(-H "Authorization: Bearer $token")
    echo "Rotated SigNoz root password and saved it to .env"
  elif [ "$password_status" = "501" ]; then
    echo "SigNoz does not support root-password rotation in this version; retaining SIGNOZ_ROOT_PASSWORD from .env" >&2
  else
    echo "SigNoz root password rotation failed with HTTP $password_status" >&2
    cat /tmp/signoz-password-rotation.json >&2
    exit 1
  fi
else
  echo "Skipping SigNoz root-password rotation in the read-only Compose provisioning job"
fi

# A valid session can be issued while the authenticated API modules are still
# being initialized. Wait for every API used below so a fresh Compose startup
# cannot fail with a transient 404 between login and provisioning.
for attempt in {1..90}; do
  if curl -fsS "$SIGNOZ_BASE_URL/api/v1/channels" "${auth[@]}" >/dev/null 2>&1 \
    && curl -fsS "$SIGNOZ_BASE_URL/api/v2/dashboards?limit=1" "${auth[@]}" >/dev/null 2>&1 \
    && curl -fsS "$SIGNOZ_BASE_URL/api/v2/rules" "${auth[@]}" >/dev/null 2>&1; then
    break
  fi
  if [ "$attempt" -eq 90 ]; then
    echo "SigNoz provisioning APIs did not become ready at $SIGNOZ_BASE_URL" >&2
    exit 1
  fi
  sleep 1
done

channel_payload='{"name":"ai-factory-local","webhook_configs":[{"send_resolved":true,"url":"http://alert-sink:8080/alerts"}]}'
channels=$(curl -fsS "$SIGNOZ_BASE_URL/api/v1/channels" "${auth[@]}")
channel_id=$(printf '%s' "$channels" | jq -r '.data[] | select(.name == "ai-factory-local") | .id' | head -1)
if [ -n "$channel_id" ]; then
  curl -fsS -X PUT "$SIGNOZ_BASE_URL/api/v1/channels/$channel_id" "${auth[@]}" \
    -H 'Content-Type: application/json' --data "$channel_payload" >/dev/null
else
  curl -fsS -X POST "$SIGNOZ_BASE_URL/api/v1/channels" "${auth[@]}" \
    -H 'Content-Type: application/json' --data "$channel_payload" >/dev/null
fi
# On a fresh organization, the channel API is writable a few seconds before
# its tenant-specific Alertmanager exists. SigNoz reports that startup state as
# 404 alertmanager_not_found, so retry the local test notification explicitly.
channel_test_response=/tmp/signoz-channel-test.json
for attempt in {1..90}; do
  if channel_test_status=$(curl -sS -o "$channel_test_response" -w '%{http_code}' \
    -X POST "$SIGNOZ_BASE_URL/api/v1/channels/test" "${auth[@]}" \
    -H 'Content-Type: application/json' --data "$channel_payload"); then
    case "$channel_test_status" in
      2*) break ;;
      404) ;;
      *)
        echo "SigNoz channel test failed with HTTP $channel_test_status" >&2
        cat "$channel_test_response" >&2
        exit 1
        ;;
    esac
  fi
  if [ "$attempt" -eq 90 ]; then
    echo "SigNoz Alertmanager did not become ready for the root organization" >&2
    cat "$channel_test_response" >&2
    exit 1
  fi
  sleep 1
done
echo "SigNoz channel ready: ai-factory-local"

dashboards=$(curl -fsS "$SIGNOZ_BASE_URL/api/v2/dashboards?limit=100" "${auth[@]}")
for dashboard_file in infrastructure/observability/signoz/dashboards/*.json; do
  dashboard_name=$(jq -er '.spec.display.name' "$dashboard_file")
  dashboard_id=$(printf '%s' "$dashboards" | jq -r --arg name "$dashboard_name" \
    '.data.dashboards[] | select(.spec.display.name == $name) | .id' | head -1)
  if [ -n "$dashboard_id" ]; then
    dashboard_resource_name=$(printf '%s' "$dashboards" | jq -er --arg id "$dashboard_id" \
      '.data.dashboards[] | select(.id == $id) | .name')
    dashboard_payload=$(jq -c --arg name "$dashboard_resource_name" 'del(.generateName) | .name = $name' "$dashboard_file")
    curl -fsS -X PUT "$SIGNOZ_BASE_URL/api/v2/dashboards/$dashboard_id" "${auth[@]}" \
      -H 'Content-Type: application/json' --data "$dashboard_payload" >/dev/null
    echo "SigNoz dashboard updated: $dashboard_name"
  else
    curl -fsS -X POST "$SIGNOZ_BASE_URL/api/v2/dashboards" "${auth[@]}" \
      -H 'Content-Type: application/json' --data-binary "@$dashboard_file" >/dev/null
    echo "SigNoz dashboard created: $dashboard_name"
  fi
done

rules=$(curl -fsS "$SIGNOZ_BASE_URL/api/v2/rules" "${auth[@]}")
while IFS= read -r rule_payload; do
  rule_name=$(printf '%s' "$rule_payload" | jq -er '.alert')
  rule_id=$(printf '%s' "$rules" | jq -r --arg name "$rule_name" '.data[] | select(.alert == $name) | .id' | head -1)
  if [ -n "$rule_id" ]; then
    curl -fsS -X PUT "$SIGNOZ_BASE_URL/api/v2/rules/$rule_id" "${auth[@]}" \
      -H 'Content-Type: application/json' --data "$rule_payload" >/dev/null
    echo "SigNoz rule updated: $rule_name"
  else
    curl -fsS -X POST "$SIGNOZ_BASE_URL/api/v2/rules" "${auth[@]}" \
      -H 'Content-Type: application/json' --data "$rule_payload" >/dev/null
    echo "SigNoz rule created: $rule_name"
  fi
done < <(jq -c '.[]' infrastructure/observability/signoz/rules/ai-factory.json)
