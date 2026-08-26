#!/bin/sh
set -eu

sed \
  -e "s|__OLLAMA_MODEL__|${OLLAMA_MODEL}|g" \
  -e "s|__OPENAI_MODEL__|${OPENAI_MODEL}|g" \
  /app/config.template.yaml > /tmp/config.yaml

exec litellm --config /tmp/config.yaml
