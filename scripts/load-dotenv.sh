#!/usr/bin/env bash

# Load Docker Compose-style KEY=VALUE entries without evaluating their contents as shell code.
load_dotenv() {
  local file=${1:-.env}
  [ -f "$file" ] || return 0
  local key value
  while IFS='=' read -r key value || [ -n "$key" ]; do
    [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
    value=${value%$'\r'}
    if [[ "$value" == \"*\" && "$value" == *\" ]]; then value=${value:1:${#value}-2}; fi
    if [[ "$value" == \'*\' && "$value" == *\' ]]; then value=${value:1:${#value}-2}; fi
    export "$key=$value"
  done < "$file"
}
