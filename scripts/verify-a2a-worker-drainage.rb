#!/usr/bin/env ruby
# frozen_string_literal: true

require "json"

build_id = ARGV.fetch(0) { abort("usage: verify-a2a-worker-drainage.rb BUILD_ID [DEPLOYMENT_JSON]") }
source = ARGV[1] ? File.read(ARGV[1]) : STDIN.read
deployment = JSON.parse(source)
routing = deployment.fetch("routingConfig")

abort("refusing to retire the current Temporal build #{build_id}") if routing["currentVersionBuildID"] == build_id
abort("refusing to retire the ramping Temporal build #{build_id}") if routing["rampingVersionBuildID"] == build_id

summary = deployment.fetch("versionSummaries").find { |version| version["BuildID"] == build_id }
abort("Temporal build is not registered: #{build_id}") if summary.nil?
abort("Temporal build is not drained: #{build_id} status=#{summary["drainageStatus"]}") unless summary["drainageStatus"] == "drained"

puts "Temporal build may be retired after evidence archival: #{build_id} status=drained"
