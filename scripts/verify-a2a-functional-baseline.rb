#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "json"

ROOT = File.expand_path("..", __dir__)
MANIFEST = File.join(ROOT, "resources/a2a/baselines/pre-cutover-functional-v1.json")

manifest = JSON.parse(File.read(MANIFEST))
failures = []

def verify_digest(entry, failures)
  path = File.join(ROOT, entry.fetch("path"))
  unless File.file?(path)
    failures << "missing #{entry.fetch('path')}"
    return
  end

  actual = Digest::SHA256.file(path).hexdigest
  failures << "#{entry.fetch('path')}: #{actual}" unless actual == entry.fetch("sha256")
end

manifest.fetch("corpora").each do |corpus|
  verify_digest(corpus, failures)
  path = File.join(ROOT, corpus.fetch("path"))
  next unless File.file?(path)

  cases = JSON.parse(File.read(path)).fetch("cases")
  failures << "#{corpus.fetch('name')} case count: #{cases.length}" unless cases.length == corpus.fetch("cases")
  failures << "#{corpus.fetch('name')} duplicate IDs" unless cases.map { |entry| entry.fetch("id") }.uniq.length == cases.length
end

manifest.fetch("artifacts").each_value { |entry| verify_digest(entry, failures) }
verify_digest(manifest.dig("chronology", "pipeline_manifest"), failures)
verify_digest(manifest.dig("consumption", "metrics"), failures)

metrics = JSON.parse(File.read(File.join(ROOT, manifest.dig("consumption", "metrics", "path"))))
failures << "sample case count" unless metrics.dig("sample", "cases") == manifest.dig("consumption", "sample_cases")
failures << "token total" unless metrics.dig("metrics", "tokens_total") == manifest.dig("consumption", "tokens_total")
failures << "duration total" unless metrics.dig("metrics", "duration_millis_total") == manifest.dig("consumption", "duration_millis_total")
failures << "cost interpretation" unless metrics.dig("cost", "interpretation") == manifest.dig("consumption", "cost_interpretation")

contract = File.read(File.join(ROOT, manifest.dig("artifacts", "output_contract", "path")))
manifest.dig("artifacts", "output_contract", "required_paths").each do |path|
  failures << "artifact contract omits #{path}" unless contract.include?(path)
end

unless failures.empty?
  warn "A2A functional baseline verification failed:"
  failures.each { |failure| warn "- #{failure}" }
  exit 1
end

total_cases = manifest.fetch("corpora").sum { |corpus| corpus.fetch("cases") }
puts "A2A functional baseline #{manifest.fetch('baseline_id')} verified (#{total_cases} cases)."
