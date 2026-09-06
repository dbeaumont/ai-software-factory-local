#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "rubygems"

policy_path = ARGV.fetch(0, "resources/a2a/supply-chain-policy-v1.json")
sbom_path = ARGV[1]
policy = JSON.parse(File.read(policy_path))

abort("unsupported supply-chain policy") unless policy["schemaVersion"] == "1.0.0"
sdk = policy.fetch("sdk")
abort("A2A SDK version drift") unless File.read("apps/a2a-agent-runtime/pom.xml")
  .include?("<a2a-java-sdk.version>#{sdk.fetch("requiredVersion")}</a2a-java-sdk.version>")
sdk.fetch("requiredArtifacts").each do |artifact|
  abort("missing required A2A SDK artifact #{artifact}") unless File.read("apps/a2a-agent-runtime/pom.xml")
    .include?("<artifactId>#{artifact}</artifactId>")
end

dockerfile = File.readlines("apps/a2a-agent-runtime/Dockerfile", chomp: true)
from = dockerfile.grep(/^FROM /)
abort("A2A runtime has no image stages") if from.empty?
abort("un-pinned A2A base image") unless from.all? { |line| line.match?(/@sha256:[a-f0-9]{64}(\s|$)/) }
abort("A2A runtime must run as non-root") unless dockerfile.any? { |line| line == "USER 10001" }

unless sbom_path.nil?
  sbom = JSON.parse(File.read(sbom_path))
  abort("SBOM is not CycloneDX") unless sbom["bomFormat"] == "CycloneDX"
  minimum = Gem::Version.new(policy.fetch("sbom").fetch("minimumCycloneDxSpecVersion"))
  abort("CycloneDX specification is too old") if Gem::Version.new(sbom.fetch("specVersion")) < minimum
  components = sbom.fetch("components")
  allowed = policy.fetch("licenses").fetch("allowed")
  denied = policy.fetch("licenses").fetch("denied")
  components.each do |component|
    licenses = component.fetch("licenses", []).map { |entry| entry.dig("license", "id") }.compact
    abort("unknown license for #{component["name"]}") if licenses.empty?
    abort("denied license for #{component["name"]}") unless (licenses & denied).empty?
    abort("unapproved license for #{component["name"]}") unless licenses.all? { |license| allowed.include?(license) }
    abort("missing component hash for #{component["name"]}") if component.fetch("hashes", []).empty?
  end
  sdk.fetch("requiredArtifacts").each do |artifact|
    component = components.find { |candidate| candidate["group"] == sdk["groupId"] && candidate["name"] == artifact }
    abort("SBOM lacks #{artifact}") if component.nil?
    abort("SBOM contains unapproved A2A SDK version") unless component["version"] == sdk["requiredVersion"]
    ids = component.fetch("licenses").map { |entry| entry.dig("license", "id") }.compact
    abort("A2A SDK license mismatch") unless ids.include?(sdk["requiredLicense"])
  end
end

signature = policy.dig("image", "signature")
abort("image signature policy is incomplete") unless signature["required"] && signature["tool"] == "cosign"
abort("SLSA provenance is not required") unless policy.dig("provenance", "required")
abort("Trivy severity gate is incomplete") unless policy.dig("vulnerabilities", "failSeverities") == %w[HIGH CRITICAL]

puts "A2A supply-chain policy verified: SDK pin, licenses, SBOM, image digests, Trivy, signature and provenance."
