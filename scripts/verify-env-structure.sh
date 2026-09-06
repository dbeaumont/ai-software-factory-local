#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
example=$(mktemp "${TMPDIR:-/tmp}/ai-factory-env-example.XXXXXX")
local_env=$(mktemp "${TMPDIR:-/tmp}/ai-factory-env-local.XXXXXX")
trap 'rm -f "$example" "$local_env"' EXIT

awk -F= '/^[A-Za-z_][A-Za-z0-9_]*=/{print $1}' .env.example > "$example"
awk -F= '/^[A-Za-z_][A-Za-z0-9_]*=/{print $1}' .env > "$local_env"
diff -u "$example" "$local_env" || {
  echo ".env and .env.example must expose variables in exactly the same order" >&2
  exit 1
}

required=(
  AI_FACTORY_A2A_RUNTIME_VERSION AI_FACTORY_A2A_REGISTRY_PROFILE
  AI_FACTORY_A2A_CARD_CACHE_MAX_TTL AI_FACTORY_A2A_CARD_CACHE_STALE_ON_OUTAGE
  AI_FACTORY_A2A_TASK_JDBC_URL AI_FACTORY_A2A_TEMPORAL_BUILD_ID
  AI_FACTORY_A2A_TLS_CERTIFICATE AI_FACTORY_A2A_OAUTH2_TOKEN_URL
  AI_FACTORY_A2A_PUSH_CALLBACK AI_FACTORY_A2A_PUSH_MAX_ATTEMPTS
  AI_FACTORY_A2A_MAX_TASKS_PER_TENANT AI_FACTORY_A2A_RATE_LIMIT_WINDOW
)
for variable in "${required[@]}"; do
  grep -qx "$variable" "$example" || { echo "Missing required A2A variable: $variable" >&2; exit 1; }
done

echo ".env and .env.example structures match, including the complete ordered A2A section."
