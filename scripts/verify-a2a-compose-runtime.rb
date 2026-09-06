#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

path = ARGV.fetch(0, "infrastructure/a2a/compose-agents.yaml")
document = YAML.safe_load(File.read(path), aliases: true)
all_services = document.fetch("services")
services = all_services.select do |name, _service|
  name.start_with?("a2a-") && !%w[a2a-task-db a2a-identity].include?(name)
end
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
  abort "#{name} endpoint must use HTTPS" unless environment.fetch("AI_FACTORY_AGENT_ENDPOINT").start_with?("https://")
  abort "#{name} must enforce mTLS" unless environment.fetch("AI_FACTORY_A2A_MTLS_REQUIRED") == "true"
  mounts = service.fetch("volumes")
  abort "#{name} must mount role PKI, card keys and the notification key" unless mounts.length == 3
  abort "#{name} must enable Temporal workers" unless
    environment.fetch("AI_FACTORY_A2A_TEMPORAL_ENABLED") == "true"
  abort "#{name} must enable durable push notifications" unless
    environment.fetch("AI_FACTORY_A2A_PUSH_ENABLED") == "true"
  abort "#{name} must enable MCP execution" unless environment.fetch("AI_FACTORY_MCP_ENABLED") == "true"
  abort "#{name} must run as UID 10001" unless service.fetch("user") == "10001:10001"
  limits = service.fetch("deploy").fetch("resources").fetch("limits")
  abort "#{name} must have bounded CPU and memory" unless limits.key?("cpus") && limits.key?("memory")
  abort "#{name} must use the durable task store" unless
    environment.fetch("AI_FACTORY_A2A_TASK_STORE_ENABLED") == "true"
  abort "#{name} must wait for the A2A database" unless
    service.fetch("depends_on").fetch("a2a-task-db").fetch("condition") == "service_healthy"
  abort "#{name} must wait for the local identity provider" unless
    service.fetch("depends_on").fetch("a2a-identity").fetch("condition") == "service_healthy"
  abort "#{name} must join the private A2A network" unless service.fetch("networks").include?("a2a-internal")
  abort "#{name} must not publish a host port" if service.key?("ports")
  role_profile = "a2a-#{role}"
  abort "#{name} must expose full and role test profiles" unless
    service.fetch("profiles").sort == ["a2a-full", role_profile].sort
end

database = all_services.fetch("a2a-task-db")
identity = all_services.fetch("a2a-identity")
abort "A2A task database must be persistent" unless database.fetch("volumes").any? do |mount|
  mount.start_with?("a2a-task-db-data:")
end
abort "A2A task database must have a healthcheck" unless database.key?("healthcheck")
abort "A2A identity provider must stay on the private A2A network" unless
  identity.fetch("networks") == ["a2a-internal"]
abort "A2A identity provider must have a healthcheck" unless identity.key?("healthcheck")

puts "A2A Compose runtime verified: #{services.length} explicit roles share #{images.first}."
