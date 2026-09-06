#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
fixture=resources/a2a/fixtures/sbom-a2a-runtime-v1.cdx.json
temporary=$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-supply-chain.XXXXXX")
trap 'rm -rf "$temporary"' EXIT

./scripts/verify-a2a-supply-chain-policy.rb resources/a2a/supply-chain-policy-v1.json "$fixture" >/dev/null
ruby -rjson - "$fixture" "$temporary/denied.json" <<'RUBY'
source, target = ARGV
bom = JSON.parse(File.read(source))
bom["components"][0]["licenses"] = [{"license" => {"id" => "GPL-3.0"}}]
File.write(target, JSON.generate(bom))
RUBY
if ./scripts/verify-a2a-supply-chain-policy.rb resources/a2a/supply-chain-policy-v1.json \
  "$temporary/denied.json" >/dev/null 2>&1; then
  echo "A2A supply-chain gate accepted a denied license" >&2
  exit 1
fi
ruby -rjson - "$fixture" "$temporary/drift.json" <<'RUBY'
source, target = ARGV
bom = JSON.parse(File.read(source))
bom["components"][0]["version"] = "0.3.0.Final"
File.write(target, JSON.generate(bom))
RUBY
if ./scripts/verify-a2a-supply-chain-policy.rb resources/a2a/supply-chain-policy-v1.json \
  "$temporary/drift.json" >/dev/null 2>&1; then
  echo "A2A supply-chain gate accepted SDK version drift" >&2
  exit 1
fi

echo "A2A supply-chain policy tests passed, including license and SDK drift rejection."
