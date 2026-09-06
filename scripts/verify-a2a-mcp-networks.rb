#!/usr/bin/env ruby
# frozen_string_literal: true

require "open3"
require "yaml"

stdout, stderr, status = Open3.capture3(
  "docker", "compose", "--env-file", ".env", "-f", "infrastructure/compose.yaml",
  "--profile", "a2a-full", "config"
)
abort stderr unless status.success?
compose = YAML.safe_load(stdout, aliases: true)
services = compose.fetch("services")

context_roles = %w[
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design security-agent threat-model independent-reviewer
]
evidence_roles = %w[supervisor test-agent test-evidence security-agent security-findings independent-reviewer]

%w[mcp-context-internal mcp-evidence-internal].each do |name|
  abort "#{name} must be internal" unless compose.fetch("networks").fetch(name).fetch("internal") == true
end

services.each do |name, service|
  next unless name.start_with?("a2a-")
  role = name.delete_prefix("a2a-")
  networks = service.fetch("networks").keys
  expected = []
  expected << "mcp-context-internal" if context_roles.include?(role)
  expected << "mcp-evidence-internal" if evidence_roles.include?(role)
  actual = networks.grep(/^mcp-/)
  abort "#{name} MCP networks #{actual.sort} differ from #{expected.sort}" unless actual.sort == expected.sort
end

abort "repository-context-mcp is missing its capability network" unless
  services.fetch("repository-context-mcp").fetch("networks").key?("mcp-context-internal")
abort "evidence-mcp is missing its capability network" unless
  services.fetch("evidence-mcp").fetch("networks").key?("mcp-evidence-internal")

puts "Role-scoped MCP Compose networks verified for all 14 A2A agents."
