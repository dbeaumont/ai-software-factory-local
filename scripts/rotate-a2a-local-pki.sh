#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
current=${1:-.local/a2a-pki}
replacement="${current}.next"
backup="${current}.previous"

case "$current" in
  ""|"/"|"."|".local") echo "Refusing unsafe A2A PKI rotation path: $current" >&2; exit 2 ;;
esac
test -d "$current" || { echo "Current A2A PKI is missing: $current" >&2; exit 2; }
test ! -e "$replacement" || { echo "Rotation staging path already exists: $replacement" >&2; exit 2; }
test ! -e "$backup" || { echo "Recoverable backup already exists: $backup" >&2; exit 2; }

./scripts/generate-a2a-local-pki.sh "$replacement"
./scripts/verify-a2a-pki.sh "$replacement"
mv "$current" "$backup"
if mv "$replacement" "$current" && ./scripts/verify-a2a-pki.sh "$current"; then
  echo "Rotated A2A PKI atomically; previous material retained at $backup"
else
  test ! -e "$current" || mv "$current" "$replacement.failed"
  mv "$backup" "$current"
  echo "A2A PKI rotation failed; previous material restored" >&2
  exit 1
fi
