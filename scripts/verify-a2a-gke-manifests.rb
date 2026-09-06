#!/usr/bin/env ruby
# frozen_string_literal: true

require "yaml"

path = ARGV.fetch(0, "infrastructure/gke/a2a/agents.generated.yaml")
documents = YAML.load_stream(File.read(path)).compact
roles = %w[
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
]

%w[Deployment Service PodDisruptionBudget HorizontalPodAutoscaler ServiceAccount SecretProviderClass].each do |kind|
  resources = documents.select { |document| document["kind"] == kind }
  abort "Expected 14 #{kind} resources, got #{resources.length}" unless resources.length == 14
end

roles.each do |role|
  name = "a2a-#{role}"
  deployment = documents.find { |document| document["kind"] == "Deployment" && document.dig("metadata", "name") == name }
  container = deployment.dig("spec", "template", "spec", "containers", 0)
  abort "#{role} image is not digest-pinned" unless container.fetch("image").include?("@sha256:")
  abort "#{role} probes are incomplete" unless container.key?("readinessProbe") && container.key?("livenessProbe")
  abort "#{role} does not use Secret Manager CSI" unless deployment.dig("spec", "template", "spec", "volumes")
    .any? { |volume| volume.dig("csi", "driver") == "secrets-store.csi.k8s.io" }
  hpa = documents.find { |document| document["kind"] == "HorizontalPodAutoscaler" && document.dig("metadata", "name") == name }
  abort "#{role} HPA is not queue-based" unless hpa.to_s.include?("temporal_task_queue_backlog")
  policy = documents.find do |document|
    document["kind"] == "NetworkPolicy" && document.dig("metadata", "name") == "#{name}-mcp"
  end
  expected_servers = []
  expected_servers << "repository-context-mcp" unless %w[test-evidence security-findings].include?(role)
  expected_servers << "evidence-mcp" if
    %w[supervisor test-agent test-evidence security-agent security-findings independent-reviewer].include?(role)
  actual_servers = policy.fetch("spec").fetch("egress").map do |egress|
    egress.dig("to", 0, "podSelector", "matchLabels", "app.kubernetes.io/name")
  end
  abort "#{role} GKE MCP boundary differs from Compose" unless actual_servers.sort == expected_servers.sort
end

deny = documents.find { |document| document.dig("metadata", "name") == "a2a-agents-default-deny" }
abort "A2A default-deny NetworkPolicy is missing" unless deny
puts "A2A GKE manifests verified: 14 isolated workloads, services, PDBs, HPAs and CSI secret mounts."
