#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

rendered = `kubectl kustomize infrastructure/gke/a2a`
abort "Cannot render A2A GKE resources" unless $?.success?
documents = YAML.load_stream(rendered).compact
agent_services = documents.select do |document|
  document["kind"] == "Service" && document.dig("metadata", "namespace") == "ai-factory-agents"
end
abort "Expected 14 private A2A Services" unless agent_services.length == 14
agent_services.each do |service|
  spec = service.fetch("spec")
  abort "#{service.dig('metadata', 'name')} is externally exposed" unless
    spec.fetch("type", "ClusterIP") == "ClusterIP" && !spec.key?("externalIPs") && !spec.key?("externalName")
end

routes = documents.select do |document|
  %w[Ingress Gateway HTTPRoute GRPCRoute].include?(document["kind"]) &&
    document.dig("metadata", "namespace") == "ai-factory-agents"
end
abort "A2A namespace contains an external route" unless routes.empty?

policies = documents.select { |document| document["kind"] == "ValidatingAdmissionPolicy" }
names = policies.map { |document| document.dig("metadata", "name") }
abort "A2A private-entry admission policies are missing" unless
  names.include?("a2a-services-cluster-private") && names.include?("a2a-routes-forbidden")
puts "A2A entry verified private: 14 ClusterIP Services, no routes, admission policies enforced."
