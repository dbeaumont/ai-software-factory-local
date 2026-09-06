#!/usr/bin/env ruby
# frozen_string_literal: true

require "base64"
require "digest"
require "json"
require "openssl"
require "pathname"
require "tempfile"

root = Pathname(ARGV.fetch(0, ".local/a2a-secrets"))
abort "Refusing unsafe A2A secret root: #{root}" if ["", "/", ".", ".local"].include?(root.to_s)
abort "A2A secret root is missing or unsafe: #{root}" unless root.directory? && !root.symlink?

roles = %w[
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
]

keys = roles.to_h do |role|
  path = root.join("roles", role, "card-jwks.json")
  abort "Missing or unsafe card JWK Set: #{path}" unless path.file? && !path.symlink?
  jwks = JSON.parse(path.read)
  entries = jwks.fetch("keys")
  abort "Card JWK Set must contain exactly one key: #{role}" unless entries.is_a?(Array) && entries.length == 1
  jwk = entries.first
  kid = jwk.fetch("kid")
  certificates = jwk.fetch("x5c")
  abort "Card signing key is malformed: #{role}" unless kid.match?(/\Aa2a-#{Regexp.escape(role)}-[A-Za-z0-9._-]+\z/) &&
    certificates.is_a?(Array) && certificates.length == 1
  certificate = OpenSSL::X509::Certificate.new(Base64.strict_decode64(certificates.first))
  [kid, Digest::SHA256.hexdigest(certificate.public_key.to_der)]
rescue JSON::ParserError, KeyError, ArgumentError, OpenSSL::X509::CertificateError => failure
  abort "Cannot derive card trust for #{role}: #{failure.message}"
end

abort "A2A card key IDs are not unique" unless keys.length == roles.length
orchestrator = root.join("orchestrator")
abort "Missing or unsafe orchestrator secret directory" unless orchestrator.directory? && !orchestrator.symlink?
target = orchestrator.join("card-trust.json")
payload = JSON.generate("version" => "1", "keys" => keys.sort.to_h)

Tempfile.create(["card-trust", ".json"], orchestrator.to_s, mode: File::RDWR, encoding: "UTF-8") do |file|
  file.chmod(0o600)
  file.write(payload)
  file.flush
  file.fsync
  File.rename(file.path, target)
end
File.chmod(0o600, target)
puts "Synchronized A2A card trust from #{roles.length} role signing certificates."
