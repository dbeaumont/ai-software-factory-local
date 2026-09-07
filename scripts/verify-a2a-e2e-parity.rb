#!/usr/bin/env ruby
# frozen_string_literal: true

require "digest"
require "json"

ROOT = File.expand_path("..", __dir__)
POST_PATH = File.join(ROOT, "resources/a2a/baselines/post-cutover-e2e-v1.json")

post = JSON.parse(File.read(POST_PATH))
failures = []

def read_verified(entry, failures)
  path = File.join(ROOT, entry.fetch("path"))
  unless File.file?(path)
    failures << "missing #{entry.fetch('path')}"
    return {}
  end
  actual = Digest::SHA256.file(path).hexdigest
  failures << "digest #{entry.fetch('path')}: #{actual}" unless actual == entry.fetch("sha256")
  JSON.parse(File.read(path))
end

baseline = read_verified(post.fetch("reference_baseline"), failures)
golden = read_verified(post.fetch("golden_contracts"), failures)
mapping = read_verified(post.fetch("a2a_contract_mapping"), failures)
expected = post.fetch("expected")

failures << "baseline id" unless baseline["baseline_id"] == post.dig("reference_baseline", "id")
failures << "golden document count" unless golden.fetch("documents", {}).size == post.dig("golden_contracts", "documents")
primary = mapping.fetch("outputs", []).select { |output| output["primary_artifact"] }
failures << "primary output count" unless primary.size == post.dig("a2a_contract_mapping", "primary_outputs")
primary.each do |output|
  failures << "missing golden #{output['output_contract']}" unless golden.fetch("documents", {}).key?(output["output_contract"])
end

corpora = baseline.fetch("corpora").to_h { |entry| [entry.fetch("name"), entry] }
cases = {}
corpora.each do |name, entry|
  path = File.join(ROOT, entry.fetch("path"))
  failures << "corpus digest #{name}" unless Digest::SHA256.file(path).hexdigest == entry.fetch("sha256")
  cases[name] = JSON.parse(File.read(path)).fetch("cases")
  failures << "case count #{name}" unless cases[name].size == entry.fetch("cases")
end
failures << "total cases" unless cases.values.sum(&:size) == expected.fetch("cases")

verdicts = Hash.new(0)
gates = Hash.new(0)
cases.fetch("short-path").each do |test_case|
  verdicts[test_case.dig("expected", "path")] += 1
  gates[test_case.dig("expected", "human_gate")] += 1
  failures << "short delegation #{test_case['id']}" unless test_case.dig("expected", "agents") == corpora.dig("short-path", "delegation_order")
  failures << "short forbidden roles #{test_case['id']}" unless test_case.dig("expected", "forbidden_agents").sort == corpora.dig("short-path", "forbidden_roles").sort
end
cases.fetch("multi-domain").each do |test_case|
  verdicts[test_case.dig("expected", "path")] += 1
  gates[test_case.dig("expected", "human_gate")] += 1
  agents = test_case.dig("expected", "agents") || test_case.dig("expected", "agents_after_approval")
  failures << "hierarchical delegation #{test_case['id']}" unless agents == corpora.dig("multi-domain", "delegation_order")
end
cases.fetch("adversarial").each { |test_case| verdicts[test_case.dig("expected", "decision")] += 1 }
cases.fetch("recovery").each do |test_case|
  recovered = test_case.dig("expected", "terminal") && test_case.dig("expected", "recovered") &&
    test_case.dig("expected", "duplicate_effects").zero?
  verdicts[recovered ? "RECOVERED_WITHOUT_DUPLICATE_EFFECT" : "RECOVERY_FAILED"] += 1
end
failures << "verdict parity #{verdicts}" unless verdicts == expected.fetch("verdicts")
failures << "gate parity #{gates}" unless gates == expected.fetch("gates")

metrics = JSON.parse(File.read(File.join(ROOT, baseline.dig("consumption", "metrics", "path"))))
failures << "token parity" unless metrics.dig("metrics", "tokens_total") == expected.fetch("tokens_total")
failures << "duration parity" unless metrics.dig("metrics", "duration_millis_total") == expected.fetch("duration_millis_total")
failures << "cost parity" unless metrics.dig("cost", "interpretation") == expected.fetch("cost_interpretation")

unless failures.empty?
  warn "A2A post-cutover E2E parity failed:"
  failures.each { |failure| warn "- #{failure}" }
  exit 1
end

puts "A2A post-cutover E2E parity verified: #{expected.fetch('cases')} cases, " \
     "#{post.dig('golden_contracts', 'documents')} contracts, " \
     "#{post.dig('a2a_contract_mapping', 'primary_outputs')} primary outputs."
