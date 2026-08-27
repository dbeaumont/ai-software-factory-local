#!/bin/sh
set -eu

export OPENAI_API_KEY="${OPENAI_API_KEY:-${VAULT_OPENAI_API_KEY:-}}"

if [ -n "${OPENAI_API_KEY:-}" ] && [ -n "${OPENAI_CA_CERT_HOST:-}" ]; then
  temporary_directory=$(mktemp -d)
  trap 'rm -rf "$temporary_directory"' EXIT

  openssl s_client -connect "$OPENAI_CA_CERT_HOST" -showcerts </dev/null 2>/dev/null \
    | awk '/-----BEGIN CERTIFICATE-----/{n++; file="'"$temporary_directory"'/openai-proxy-ca-" n ".crt"} file{print > file} /-----END CERTIFICATE-----/{file=""}'

  set -- "$temporary_directory"/*.crt
  if [ ! -f "$1" ]; then
    echo "Unable to retrieve a certificate chain from $OPENAI_CA_CERT_HOST" >&2
    exit 1
  fi
  certificate_bundle="${SSL_CERT_FILE:-/etc/ssl/cert.pem}"
  if [ ! -f "$certificate_bundle" ]; then
    echo "System CA bundle not found at $certificate_bundle" >&2
    exit 1
  fi
  cat "$certificate_bundle" "$temporary_directory"/*.crt > /tmp/openai-ca-bundle.pem
  export SSL_CERT_FILE=/tmp/openai-ca-bundle.pem
  export AI_FACTORY_LEGACY_CA_COMPATIBILITY=true
  export PYTHONPATH=/app/python${PYTHONPATH:+:$PYTHONPATH}
fi

sed \
  -e "s|__OLLAMA_MODEL__|${OLLAMA_MODEL}|g" \
  -e "s|__OPENAI_MODEL__|${OPENAI_MODEL}|g" \
  /app/config.template.yaml > /tmp/config.yaml

exec litellm --config /tmp/config.yaml
