#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

path = ARGV.fetch(0, "infrastructure/a2a/compose-agents.yaml")
document = YAML.safe_load(File.read(path), aliases: true)
services = document.fetch("services")
roles = %w[
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
]

expected = roles.map { |role| "a2a-#{role}" }.sort
actual = services.keys.sort
abort "A2A Compose services differ: #{actual}" unless actual == expected

images = services.values.map { |service| service.fetch("image") }.uniq
abort "A2A roles do not share one runtime image: #{images}" unless images.length == 1

services.each do |name, service|
  abort "#{name} must not set container_name" if service.key?("container_name")
  role = name.delete_prefix("a2a-")
  environment = service.fetch("environment")
  abort "#{name} role mismatch" unless environment.fetch("AI_FACTORY_AGENT_ROLE") == role
  abort "#{name} endpoint mismatch" unless environment.fetch("AI_FACTORY_AGENT_ENDPOINT").include?(name)
  abort "#{name} must run as UID 10001" unless service.fetch("user") == "10001:10001"
  abort "#{name} must join the private A2A network" unless service.fetch("networks").include?("a2a-internal")
  abort "#{name} must not publish a host port" if service.key?("ports")
end

puts "A2A Compose runtime verified: #{services.length} explicit roles share #{images.first}."
