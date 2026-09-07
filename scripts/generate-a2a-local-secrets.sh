#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

output=${1:-.local/a2a-secrets}
if [ "$output" = "/" ] || [ "$output" = "." ] || [ -z "$output" ]; then
  echo "Refusing unsafe A2A secret output path: $output" >&2
  exit 2
fi
if [ -e "$output" ]; then
  echo "A2A local secrets already exist at $output; refusing to overwrite them." >&2
  exit 2
fi

stage=$(mktemp -d "${TMPDIR:-/tmp}/ai-factory-a2a-secrets.XXXXXX")
trap 'rm -rf "$stage"' EXIT
chmod 700 "$stage"

write_hex() {
  local path="$1"
  local bytes="$2"
  openssl rand -hex "$bytes" > "$path"
  chmod 600 "$path"
}

generate_jwks() {
  local identity="$1"
  local path="$2"
  ruby -ropenssl -rjson -rbase64 - "$identity" "$path" <<'RUBY'
identity, path = ARGV
key = OpenSSL::PKey::RSA.new(3072)
certificate = OpenSSL::X509::Certificate.new
certificate.version = 2
certificate.serial = 1
certificate.subject = OpenSSL::X509::Name.parse("/CN=#{identity}.a2a.local/O=AI Factory")
certificate.issuer = certificate.subject
certificate.public_key = key.public_key
certificate.not_before = Time.now - 60
certificate.not_after = Time.now + (365 * 24 * 60 * 60)
certificate.sign(key, OpenSSL::Digest.new("SHA256"))
encode = ->(integer) { Base64.urlsafe_encode64(integer.to_s(2), padding: false) }
jwk = {
  "kty" => "RSA", "use" => "sig", "alg" => "RS256", "kid" => "a2a-#{identity}-local-v1",
  "n" => encode.call(key.n), "e" => encode.call(key.e), "d" => encode.call(key.d),
  "p" => encode.call(key.p), "q" => encode.call(key.q), "dp" => encode.call(key.dmp1),
  "dq" => encode.call(key.dmq1), "qi" => encode.call(key.iqmp),
  "x5c" => [Base64.strict_encode64(certificate.to_der)]
}
File.write(path, JSON.generate("keys" => [jwk]))
RUBY
  chmod 600 "$path"
}

mkdir -p "$stage/orchestrator" "$stage/roles"
chmod 700 "$stage/orchestrator" "$stage/roles"
write_hex "$stage/orchestrator/oauth2-client-secret" 32
write_hex "$stage/orchestrator/push-hmac-key" 32

roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
for role in "${roles[@]}"; do
  directory="$stage/roles/$role"
  mkdir -p "$directory"
  chmod 700 "$directory"
  write_hex "$directory/oauth2-client-secret" 32
  write_hex "$directory/mcp-access-token" 32
  write_hex "$directory/push-hmac-key" 32
  generate_jwks "$role" "$directory/card-jwks.json"
done

ruby -ropenssl -rjson -rbase64 -rdigest - "$stage" "${roles[@]}" <<'RUBY'
stage, *roles = ARGV
keys = roles.to_h do |role|
  jwks = JSON.parse(File.read(File.join(stage, "roles", role, "card-jwks.json")))
  jwk = jwks.fetch("keys").first
  certificate = OpenSSL::X509::Certificate.new(Base64.strict_decode64(jwk.fetch("x5c").first))
  [jwk.fetch("kid"), Digest::SHA256.hexdigest(certificate.public_key.to_der)]
end
File.write(File.join(stage, "orchestrator", "card-trust.json"), JSON.generate(
  "version" => "1", "keys" => keys.sort.to_h))
RUBY
chmod 600 "$stage/orchestrator/card-trust.json"

mkdir -p "$(dirname "$output")"
mv "$stage" "$output"
trap - EXIT
echo "Generated role-isolated local A2A secrets at $output"
