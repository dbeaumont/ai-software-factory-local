#!/usr/bin/env ruby
# frozen_string_literal: true

require "date"
require "digest"
require "json"

path = ARGV.fetch(0) { abort("usage: verify-a2a-rollback-gate.rb GATE.json") }
gate = JSON.parse(File.read(path))

def require_keys(object, keys, name)
  abort("#{name} must be an object") unless object.is_a?(Hash)
  missing = keys - object.keys
  abort("#{name} is missing: #{missing.join(', ')}") unless missing.empty?
end

def digest!(value, name)
  abort("#{name} must be a sha256 digest") unless value.is_a?(String) && value.match?(/\A[0-9a-f]{64}\z/)
end

def timestamp!(value, name)
  DateTime.iso8601(value)
rescue ArgumentError, TypeError
  abort("#{name} must be an ISO-8601 timestamp")
end

def image!(value, name)
  abort("#{name} must be an immutable image reference") unless value.is_a?(String) &&
    value.match?(%r{\A[a-zA-Z0-9._:/-]+@sha256:[0-9a-f]{64}\z})
end

def canonical(value)
  case value
  when Hash then value.keys.sort.to_h { |key| [key, canonical(value.fetch(key))] }
  when Array then value.map { |item| canonical(item) }
  else value
  end
end

require_keys(gate, %w[schemaVersion gateId decision incident source target taskInventory backups
                       reconciliation compatibility approval], "gate")
abort("unsupported rollback gate schema") unless gate["schemaVersion"] == "1"
abort("unexpected rollback gate ID") unless gate["gateId"] == "A2A-206"
abort("rollback gate is not APPROVED") unless gate["decision"] == "APPROVED"

incident = gate["incident"]
require_keys(incident, %w[id openedAt evidenceDigest], "incident")
abort("incident ID is invalid") unless incident["id"].is_a?(String) && incident["id"].match?(/\A[A-Z][A-Z0-9-]{2,63}\z/)
timestamp!(incident["openedAt"], "incident.openedAt")
digest!(incident["evidenceDigest"], "incident.evidenceDigest")

%w[source target].each do |side|
  release = gate[side]
  require_keys(release, %w[commit orchestratorImage runtimeImage temporalBuildId], side)
  abort("#{side}.commit must be a full Git commit") unless release["commit"].is_a?(String) &&
    release["commit"].match?(/\A[0-9a-f]{40}\z/)
  image!(release["orchestratorImage"], "#{side}.orchestratorImage")
  image!(release["runtimeImage"], "#{side}.runtimeImage")
  abort("#{side}.temporalBuildId is invalid") unless release["temporalBuildId"].is_a?(String) &&
    release["temporalBuildId"].match?(/\A[A-Za-z0-9._-]{1,128}\z/)
end
abort("source and target releases must differ") if gate["source"] == gate["target"]

inventory = gate["taskInventory"]
states = %w[SUBMITTED WORKING INPUT_REQUIRED AUTH_REQUIRED COMPLETED REJECTED FAILED CANCELED]
require_keys(inventory, %w[total states digest capturedAt], "taskInventory")
require_keys(inventory["states"], states, "taskInventory.states")
abort("task inventory counts must be non-negative integers") unless inventory["states"].values.all? do |count|
  count.is_a?(Integer) && count >= 0
end
abort("task inventory total is inconsistent") unless inventory["total"].is_a?(Integer) &&
  inventory["total"] == inventory["states"].values.sum
digest!(inventory["digest"], "taskInventory.digest")
timestamp!(inventory["capturedAt"], "taskInventory.capturedAt")

abort("backups must be an array") unless gate["backups"].is_a?(Array)
authorities = %w[temporal a2a-task-store evidence orchestrator-projection]
abort("rollback gate requires exactly the four authoritative backups") unless
  gate["backups"].map { |backup| backup["authority"] }.sort == authorities.sort
gate["backups"].each do |backup|
  require_keys(backup, %w[authority reference digest status verifiedAt], "backup")
  abort("backup reference is invalid") unless backup["reference"].is_a?(String) && !backup["reference"].empty?
  digest!(backup["digest"], "backup.digest")
  abort("backup is not verified") unless backup["status"] == "VERIFIED"
  timestamp!(backup["verifiedAt"], "backup.verifiedAt")
end

reconciliation = gate["reconciliation"]
require_keys(reconciliation, %w[status digest total lost duplicates unresolved], "reconciliation")
abort("reconciliation did not pass") unless reconciliation["status"] == "PASS"
digest!(reconciliation["digest"], "reconciliation.digest")
abort("reconciliation does not cover the task inventory") unless reconciliation["total"] == inventory["total"]
%w[lost duplicates unresolved].each do |counter|
  abort("reconciliation #{counter} must be zero") unless reconciliation[counter] == 0
end

compatibility = gate["compatibility"]
require_keys(compatibility, %w[status digest databaseFloor replayErrors unsupportedContracts], "compatibility")
abort("compatibility did not pass") unless compatibility["status"] == "PASS"
digest!(compatibility["digest"], "compatibility.digest")
abort("rollback target is below the cancellation outbox floor") unless compatibility["databaseFloor"] == "V005"
abort("Temporal replay errors remain") unless compatibility["replayErrors"] == 0
abort("unsupported A2A contracts remain") unless compatibility["unsupportedContracts"] == 0

approval = gate["approval"]
require_keys(approval, %w[identity role decision decidedAt boundDigest], "approval")
abort("rollback requires an explicit operations approval") unless approval["role"] == "OPERATIONS" &&
  approval["decision"] == "APPROVED" && approval["identity"].is_a?(String) && !approval["identity"].strip.empty?
timestamp!(approval["decidedAt"], "approval.decidedAt")
digest!(approval["boundDigest"], "approval.boundDigest")
unsigned = Marshal.load(Marshal.dump(gate))
unsigned["approval"].delete("boundDigest")
expected = Digest::SHA256.hexdigest(JSON.generate(canonical(unsigned)))
abort("operations approval is not bound to this exact gate manifest") unless approval["boundDigest"] == expected

puts "A2A rollback gate APPROVED incident=#{incident['id']} source=#{gate['source']['commit']} target=#{gate['target']['commit']} tasks=#{inventory['total']} bound_digest=#{expected}"
