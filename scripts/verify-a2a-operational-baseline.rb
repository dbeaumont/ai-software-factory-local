#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "json"

ROOT = File.expand_path("..", __dir__)
MANIFEST = File.join(ROOT, "resources/a2a/baselines/pre-cutover-operational-v1.json")

baseline = JSON.parse(File.read(MANIFEST))
failures = []

baseline.fetch("source").each_value do |entry|
  next unless entry.is_a?(Hash) && entry.key?("path")

  path = File.join(ROOT, entry.fetch("path"))
  if !File.file?(path)
    failures << "missing #{entry.fetch('path')}"
  elsif Digest::SHA256.file(path).hexdigest != entry.fetch("sha256")
    failures << "digest mismatch for #{entry.fetch('path')}"
  end
end

rows = File.readlines(File.join(ROOT, baseline.dig("source", "results", "path")), chomp: true)
  .reject(&:empty?).map { |line| JSON.parse(line) }

def distribution(values)
  ordered = values.sort
  rank = ->(quantile) { ordered[[(quantile * ordered.length).ceil - 1, 0].max] }
  {
    "min" => ordered.first,
    "p50" => rank.call(0.50),
    "p95" => rank.call(0.95),
    "p99" => rank.call(0.99),
    "max" => ordered.last,
    "mean" => (ordered.sum.to_f / ordered.length).round(2)
  }
end

failures << "sample size" unless rows.length == baseline.dig("sample", "cases")
status_counts = rows.group_by { |row| row.fetch("status") }.transform_values(&:length)
failures << "status counts" unless status_counts == baseline.dig("sample", "status_counts")
failures << "latency distribution" unless distribution(rows.map { |row| row.fetch("duration_millis") }) ==
  baseline.fetch("latency_millis").slice("min", "p50", "p95", "p99", "max", "mean")
token_distribution = distribution(rows.map { |row| row.fetch("tokens") })
token_distribution["total"] = rows.sum { |row| row.fetch("tokens") }
failures << "token distribution" unless token_distribution ==
  baseline.fetch("tokens").slice("min", "p50", "p95", "p99", "max", "mean", "total")

failed = rows.count { |row| row.fetch("status") == "FAILED" }
failures << "failure count" unless failed == baseline.dig("failure", "count")
failures << "failure rate" unless (failed.to_f / rows.length).round(4) == baseline.dig("failure", "rate")

cases = JSON.parse(File.read(File.join(ROOT, baseline.dig("source", "cases", "path")))).fetch("tasks")
payload_sizes = cases.map do |task|
  JSON.generate({
    repositoryUrl: "http://gitea:3000/aiadmin/#{task.fetch('repository')}.git",
    baseBranch: "main",
    requirement: task.fetch("requirement"),
    llmMode: "CLOUD"
  }).bytesize
end
failures << "submission payload distribution" unless distribution(payload_sizes) ==
  baseline.fetch("payload_bytes").slice("min", "p50", "p95", "p99", "max", "mean")

failures << "cost absence misrepresented" unless baseline.dig("cost_micros", "status") ==
  "UNAVAILABLE_NOT_ZERO"
failures << "memory absence misrepresented" unless baseline.dig("memory_bytes", "status") == "UNAVAILABLE"
failures << "role attribution absence misrepresented" unless baseline.dig("per_role", "status") ==
  "UNAVAILABLE_MONOLITHIC_AGGREGATE"
failures << "role count" unless baseline.dig("per_role", "roles").uniq.length == 14

unless failures.empty?
  warn "A2A operational baseline verification failed:"
  failures.each { |failure| warn "- #{failure}" }
  exit 1
end

puts "A2A operational baseline #{baseline.fetch('baseline_id')} verified (#{rows.length} serial cases)."
