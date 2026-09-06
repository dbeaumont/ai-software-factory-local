#!/usr/bin/env ruby
# frozen_string_literal: true

require "open3"
require "yaml"

ROOT = File.expand_path("..", __dir__)
MANIFEST = File.join(ROOT, "resources/temporal/cutover-freeze-v1.yaml")

def capture(*command)
  output, error, status = Open3.capture3(*command, chdir: ROOT)
  [output.strip, error.strip, status.success?]
end

manifest = YAML.safe_load(File.read(MANIFEST), [], [], false)
baseline = manifest.fetch("baselineCommit")
failures = []

manifest.fetch("frozenObjects").each do |name, entry|
  tracked_path = entry.fetch("path")
  expected = entry.fetch("object")
  actual, error, found = capture("git", "rev-parse", "#{baseline}:#{tracked_path}")
  failures << "#{name}: baseline object unavailable (#{error})" unless found
  failures << "#{name}: expected #{expected}, baseline contains #{actual}" if found && actual != expected

  _diff, _diff_error, unchanged = capture("git", "diff", "--quiet", baseline, "--", tracked_path)
  failures << "#{name}: tracked content changed after freeze" unless unchanged
  untracked, _untracked_error, listed = capture(
    "git", "ls-files", "--others", "--exclude-standard", "--", tracked_path
  )
  failures << "#{name}: cannot inspect untracked content" unless listed
  failures << "#{name}: untracked content added: #{untracked.lines.first&.strip}" unless untracked.empty?
end

unless failures.empty?
  warn "Temporal cutover freeze verification failed:"
  failures.each { |failure| warn "- #{failure}" }
  exit 1
end

puts "Temporal cutover freeze #{manifest.fetch('freezeId')} verified at #{baseline}."
