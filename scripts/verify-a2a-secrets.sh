#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
root=${1:-.local/a2a-secrets}

mode_of() {
  stat -f '%Lp' "$1" 2>/dev/null || stat -c '%a' "$1"
}

verify_file() {
  local path="$1"
  test -f "$path" && test ! -L "$path" || { echo "Unsafe or missing A2A secret: $path" >&2; exit 1; }
  test "$(mode_of "$path")" = "600" || { echo "A2A secret must have mode 0600: $path" >&2; exit 1; }
  test -s "$path" || { echo "Empty A2A secret: $path" >&2; exit 1; }
}

verify_file "$root/orchestrator/oauth2-client-secret"
verify_file "$root/orchestrator/push-hmac-key"
roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
for role in "${roles[@]}"; do
  directory="$root/roles/$role"
  verify_file "$directory/oauth2-client-secret"
  verify_file "$directory/mcp-access-token"
  verify_file "$directory/push-hmac-key"
  verify_file "$directory/card-jwks.json"
  ruby -rjson -e '
    keys = JSON.parse(File.read(ARGV.fetch(0))).fetch("keys")
    required = %w[kty use alg kid n e d p q dp dq qi x5c]
    abort("invalid private RSA JWK Set") unless keys.length == 1 && required.all? { |field| keys[0][field] }
  ' "$directory/card-jwks.json"
done

ruby -rjson -e '
  policy = JSON.parse(File.read("resources/a2a/secret-policy-v1.json"))
  abort("invalid A2A secret policy") unless policy["delivery"]["mechanism"] == "read-only-file-mount"
  abort("raw environment secrets permitted") unless policy["delivery"]["rawEnvironmentVariablesAllowed"] == false
  abort("missing forbidden sinks") unless policy["forbiddenSinks"].length >= 8
'

echo "A2A secret mounts verified: inventory, owner-only permissions and private signing keys are valid."
