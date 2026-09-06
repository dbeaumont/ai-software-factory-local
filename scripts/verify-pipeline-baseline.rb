#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "json"
require "open3"
require "yaml"

ROOT = File.expand_path("..", __dir__)
MANIFEST = File.join(ROOT, "resources/multiagents/baselines/pipeline-v1.yaml")

def capture(*command)
  output, error, status = Open3.capture3(*command, chdir: ROOT)
  abort "#{command.join(' ')}: #{error.strip}" unless status.success?
  output.strip
end

manifest = YAML.safe_load(File.read(MANIFEST), [], [], false)
failures = []

source = manifest.fetch("source")
actual_commit = capture("git", "rev-parse", "#{source.fetch('tag')}^{commit}")
failures << "tag commit: #{actual_commit}" unless actual_commit == source.fetch("commit")

actual_tree = capture("git", "rev-parse", "#{source.fetch('tag')}^{tree}")
failures << "source tree: #{actual_tree}" unless actual_tree == source.fetch("tree")

manifest.fetch("gitObjects").each do |name, entry|
  actual = capture("git", "rev-parse", entry.fetch("revisionPath"))
  failures << "#{name}: #{actual}" unless actual == entry.fetch("object")
end

manifest.fetch("referenceCampaign").each_value do |entry|
  next unless entry.is_a?(Hash) && entry.key?("sha256")

  path = File.join(ROOT, entry.fetch("path"))
  actual = Digest::SHA256.file(path).hexdigest
  failures << "#{entry.fetch('path')}: #{actual}" unless actual == entry.fetch("sha256")
end
report_path = File.join(ROOT, manifest.dig("referenceCampaign", "report"))
failures << "missing campaign report" unless File.file?(report_path)

metrics_path = File.join(ROOT, "resources/multiagents/baselines/pipeline-v1-metrics.json")
metrics = JSON.parse(File.read(metrics_path))
failures << "metrics baseline ID" unless metrics.fetch("baseline_id") == manifest.fetch("baselineId")
failures << "empty baseline corpus" unless metrics.dig("sample", "cases").to_i.positive?
failures << "missing pipeline states" unless metrics.dig("sample", "status_counts").is_a?(Hash) &&
  !metrics.dig("sample", "status_counts").empty?
failures << "missing duration" unless metrics.dig("metrics", "duration_millis_total").to_i.positive?
failures << "missing token usage" unless metrics.dig("metrics", "tokens_total").to_i.positive?
failures << "unknown cost interpreted as zero" unless metrics.dig("cost", "interpretation") ==
  "UNAVAILABLE_NOT_ZERO"
failures << "invalid source digest" unless metrics.dig("source_artifact", "sha256")&.match?(/\A[0-9a-f]{64}\z/)
failures << "metrics source path diverges" unless metrics.dig("source_artifact", "path") ==
  manifest.dig("referenceCampaign", "baselineArtifact", "path")

unless failures.empty?
  warn "Pipeline baseline verification failed:"
  failures.each { |failure| warn "- #{failure}" }
  exit 1
end

puts "Pipeline baseline #{manifest.fetch('baselineId')} verified."
