#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ENV_FILE=${AI_FACTORY_INIT_ENV_FILE:-.env}
VAULT_FILE=${AI_FACTORY_INIT_VAULT_FILE:-.vault}

if [ ! -f "$ENV_FILE" ] || [ ! -f "$VAULT_FILE" ]; then
  echo "Local configuration files must exist before secret initialization." >&2
  exit 1
fi

read_value() {
  local path="$1"
  local key="$2"
  awk -v key="$key" 'index($0, key "=") == 1 { print substr($0, length(key) + 2); exit }' "$path"
}

write_value() {
  local path="$1"
  local key="$2"
  local value="$3"
  local temporary
  temporary=$(mktemp "${TMPDIR:-/tmp}/ai-factory-config.XXXXXX")
  awk -v key="$key" -v value="$value" '
    BEGIN { updated = 0 }
    index($0, key "=") == 1 { print key "=" value; updated = 1; next }
    { print }
    END { if (!updated) print key "=" value }
  ' "$path" > "$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$path"
}

remove_key() {
  local path="$1"
  local key="$2"
  local temporary
  temporary=$(mktemp "${TMPDIR:-/tmp}/ai-factory-config.XXXXXX")
  awk -v key="$key" 'index($0, key "=") != 1 { print }' "$path" > "$temporary"
  chmod 600 "$temporary"
  mv "$temporary" "$path"
}

generate_hex() {
  local bytes="$1"
  openssl rand -hex "$bytes"
}

ensure_env_secret() {
  local key="$1"
  local bytes="$2"
  local minimum_length="$3"
  local value
  value=$(read_value "$ENV_FILE" "$key")
  if [ -z "$value" ]; then
    value=$(generate_hex "$bytes")
    write_value "$ENV_FILE" "$key" "$value"
    echo "Generated local secret: $key"
  elif [ "${#value}" -lt "$minimum_length" ]; then
    echo "$key must contain at least $minimum_length characters." >&2
    exit 1
  fi
}

ensure_shared_secret() {
  local key="$1"
  local bytes="$2"
  local minimum_length="$3"
  local env_value vault_value value
  env_value=$(read_value "$ENV_FILE" "$key")
  vault_value=$(read_value "$VAULT_FILE" "$key")

  if [ -n "$env_value" ] && [ -n "$vault_value" ] && [ "$env_value" != "$vault_value" ]; then
    echo "$key differs between $ENV_FILE and $VAULT_FILE; refusing to replace an established secret." >&2
    exit 1
  fi

  value=${env_value:-$vault_value}
  if [ -z "$value" ]; then
    value=$(generate_hex "$bytes")
    echo "Generated shared local secret: $key"
  elif [ "${#value}" -lt "$minimum_length" ]; then
    echo "$key must contain at least $minimum_length characters." >&2
    exit 1
  fi

  write_value "$ENV_FILE" "$key" "$value"
  write_value "$VAULT_FILE" "$key" "$value"
}

sync_optional_secret() {
  local key="$1"
  local minimum_length="$2"
  local env_value vault_value value
  env_value=$(read_value "$ENV_FILE" "$key")
  vault_value=$(read_value "$VAULT_FILE" "$key")

  if [ -n "$env_value" ] && [ -n "$vault_value" ] && [ "$env_value" != "$vault_value" ]; then
    echo "$key differs between $ENV_FILE and $VAULT_FILE; refusing to replace an established secret." >&2
    exit 1
  fi

  value=${env_value:-$vault_value}
  if [ -z "$value" ]; then
    return
  fi
  if [ "${#value}" -lt "$minimum_length" ]; then
    echo "$key must contain at least $minimum_length characters." >&2
    exit 1
  fi

  write_value "$ENV_FILE" "$key" "$value"
  write_value "$VAULT_FILE" "$key" "$value"
}

ensure_env_secret ARTIFACTORY_DB_PASSWORD 24 1
ensure_env_secret AI_FACTORY_SANDBOX_RUNNER_TOKEN 32 32
ensure_shared_secret APPROVAL_ATTESTATION_KEY 32 32
ensure_shared_secret JF_SHARED_SECURITY_MASTERKEY 16 32
ensure_shared_secret JF_SHARED_SECURITY_JOINKEY 16 32
sync_optional_secret GITEA_TOKEN 16

# Existing local installations are migrated away from the legacy Docker socket runtime.
write_value "$ENV_FILE" AI_FACTORY_SANDBOX_RUNTIME compose
remove_key "$ENV_FILE" DOCKER_SOCKET_GID

chmod 600 "$ENV_FILE" "$VAULT_FILE"
