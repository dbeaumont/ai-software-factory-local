#!/usr/bin/env ruby
# frozen_string_literal: true

require "open3"
require "yaml"

stdout, stderr, status = Open3.capture3(
  "docker", "compose", "--env-file", ".env", "-f", "infrastructure/compose.yaml", "config"
)
abort stderr unless status.success?
compose = YAML.safe_load(stdout, aliases: true)
network = compose.fetch("networks").fetch("a2a-internal")
abort "a2a-internal must be an internal network" unless network.fetch("internal") == true

members = compose.fetch("services").each_with_object([]) do |(name, service), result|
  result << name if service.fetch("networks", {}).key?("a2a-internal")
end.sort
expected = (["orchestrator"] + %w[
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
].map { |role| "a2a-#{role}" }).sort

abort "Unexpected A2A network members: #{members}" unless members == expected
members.grep(/^a2a-/).each do |name|
  abort "#{name} exposes a host port" if compose.fetch("services").fetch(name).key?("ports")
end

puts "A2A internal network verified for orchestrator and 14 private agent endpoints."
