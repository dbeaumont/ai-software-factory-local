#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
temporary=$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-secret-test.XXXXXX")
trap 'rm -rf "$temporary"' EXIT

./scripts/generate-a2a-local-secrets.sh "$temporary/current" >/dev/null
./scripts/generate-a2a-local-secrets.sh "$temporary/replacement" >/dev/null
./scripts/verify-a2a-secrets.sh "$temporary/current" >/dev/null
./scripts/verify-a2a-secrets.sh "$temporary/replacement" >/dev/null

old_digest=$(openssl sha256 "$temporary/current/roles/developer/mcp-access-token")
staged="$temporary/current/roles/developer/mcp-access-token.new"
cp "$temporary/replacement/roles/developer/mcp-access-token" "$staged"
chmod 600 "$staged"
mv "$staged" "$temporary/current/roles/developer/mcp-access-token"
new_digest=$(openssl sha256 "$temporary/current/roles/developer/mcp-access-token")
test "$old_digest" != "$new_digest" || { echo "A2A secret rotation retained the old value" >&2; exit 1; }
./scripts/verify-a2a-secrets.sh "$temporary/current" >/dev/null

chmod 640 "$temporary/current/roles/developer/mcp-access-token"
if ./scripts/verify-a2a-secrets.sh "$temporary/current" >/dev/null 2>&1; then
  echo "A2A secret verifier accepted unsafe permissions" >&2
  exit 1
fi

echo "A2A secret rotation tests passed, including atomic replacement and permission rejection."
