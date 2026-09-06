#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

temporary=$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-pki-test.XXXXXX")
trap 'rm -rf "$temporary"' EXIT
pki="$temporary/pki"

./scripts/generate-a2a-local-pki.sh "$pki" >/dev/null
./scripts/verify-a2a-pki.sh "$pki" >/dev/null

(cd "$pki/authority" && openssl ca -config openssl.cnf \
  -revoke ../workloads/developer/tls.crt >/dev/null 2>&1 \
  && openssl ca -config openssl.cnf -gencrl -out ca.crl >/dev/null 2>&1)
if ./scripts/verify-a2a-pki.sh "$pki" >/dev/null 2>&1; then
  echo "Revoked A2A certificate was accepted" >&2
  exit 1
fi

echo "A2A local PKI tests passed, including fail-closed revocation."
