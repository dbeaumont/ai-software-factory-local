#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
temporary=$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-pki-rotation.XXXXXX")
trap 'rm -rf "$temporary"' EXIT
current="$temporary/current"

./scripts/generate-a2a-local-pki.sh "$current" >/dev/null
before=$(openssl x509 -in "$current/workloads/developer/tls.crt" -noout -fingerprint -sha256)
./scripts/rotate-a2a-local-pki.sh "$current" >/dev/null
after=$(openssl x509 -in "$current/workloads/developer/tls.crt" -noout -fingerprint -sha256)
previous=$(openssl x509 -in "$current.previous/workloads/developer/tls.crt" -noout -fingerprint -sha256)

test "$before" = "$previous" || { echo "Rotation did not preserve the previous PKI" >&2; exit 1; }
test "$before" != "$after" || { echo "Rotation did not issue new certificates" >&2; exit 1; }
./scripts/verify-a2a-pki.sh "$current" >/dev/null
./scripts/verify-a2a-pki.sh "$current.previous" >/dev/null
echo "A2A local PKI rotation test passed with a recoverable previous generation."
