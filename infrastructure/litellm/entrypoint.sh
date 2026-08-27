#!/bin/sh
set -eu

export OPENAI_API_KEY="${OPENAI_API_KEY:-${VAULT_OPENAI_API_KEY:-}}"

sed \
  -e "s|__OLLAMA_MODEL__|${OLLAMA_MODEL}|g" \
  -e "s|__OPENAI_MODEL__|${OPENAI_MODEL}|g" \
  /app/config.template.yaml > /tmp/config.yaml

exec litellm --config /tmp/config.yaml
