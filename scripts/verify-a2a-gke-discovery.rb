#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"
require "ipaddr"
require "yaml"
require "uri"

registry = JSON.parse(File.read("resources/a2a/agent-registry-v1.json")).fetch("profiles").fetch("gke")
documents = YAML.load_stream(File.read("infrastructure/gke/a2a/agents.generated.yaml")).compact
services = documents.select { |document| document["kind"] == "Service" }.map do |service|
  [service.dig("metadata", "name"), service.dig("metadata", "namespace")]
end.to_h

abort "GKE registry must contain exactly the 14 rendered agent services" unless registry.length == 14 &&
  registry.keys.sort == services.keys.map { |name| name.delete_prefix("a2a-") }.sort

registry.each do |role, raw|
  uri = URI(raw)
  service = "a2a-#{role}"
  expected = "#{service}.#{services.fetch(service)}.svc.cluster.local"
  abort "#{role} does not use HTTPS" unless uri.scheme == "https"
  abort "#{role} does not use stable cluster DNS: #{uri.host}" unless uri.host == expected && uri.port == 8090
  begin
    IPAddr.new(uri.host)
    abort "#{role} registry must never use a pod IP"
  rescue IPAddr::InvalidAddressError
    # Expected: a stable Kubernetes DNS name.
  end
end

discovery = YAML.safe_load(File.read("infrastructure/gke/a2a/orchestrator-discovery.yaml"))
abort "Orchestrator does not select the GKE registry" unless discovery.dig("data", "AI_FACTORY_A2A_REGISTRY_PROFILE") == "gke"
puts "A2A GKE discovery verified: 14 private, stable, allow-listed Service DNS names and no pod/public address."
