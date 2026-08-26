#!/bin/sh
set -eu

if [ -z "${OPENAI_API_KEY:-}" ] && [ -n "${VAULT_OPENAI_API_KEY:-}" ]; then
  export OPENAI_API_KEY="$VAULT_OPENAI_API_KEY"
fi

sed \
  -e "s|__OLLAMA_MODEL__|${OLLAMA_MODEL}|g" \
  -e "s|__OPENAI_MODEL__|${OPENAI_MODEL}|g" \
  /app/config.template.yaml > /tmp/config.yaml

exec litellm --config /tmp/config.yaml
