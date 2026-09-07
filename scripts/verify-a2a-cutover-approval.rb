#!/usr/bin/env ruby
# frozen_string_literal: true

require "pathname"
require "rbconfig"
require "time"

root = Pathname.new(File.expand_path("..", __dir__))
gate_path = root.join("docs/qualification/a2a/GATE-A2A-180-CUTOVER.md")
approval_path = root.join("docs/evidence/a2a/A2A-189-CUTOVER-APPROVAL.md")
manifest_verifier = root.join("scripts/generate-a2a-evidence-manifest.rb")

abort("A2A cutover gate is missing") unless gate_path.file?
gate = gate_path.read

status = gate[/^> Statut : `([^`]+)`$/, 1]
abort("A2A cutover gate is not APPROVED (status=#{status || 'missing'})") unless status == "APPROVED"

candidate = gate[/^> Candidat source : `([0-9a-f]{40})`$/, 1]
abort("A2A cutover candidate is missing or invalid") unless candidate

def section(document, heading, next_heading)
  value = document[/^#{Regexp.escape(heading)}\n(.*?)^#{Regexp.escape(next_heading)}\n/m, 1]
  abort("missing section: #{heading}") unless value
  value
end

def table_rows(markdown)
  markdown.lines.filter_map do |line|
    next unless line.start_with?("|")

    cells = line.split("|")[1...-1].map { |cell| cell.strip.delete_prefix("`").delete_suffix("`") }
    next if cells.empty? || cells.first.match?(/\A[-:]+\z/)

    cells
  end
end

qualification = table_rows(section(gate, "## Matrice de qualification", "## Seuils obligatoires"))
qualification.shift if qualification.first&.first == "Domaine"
abort("A2A qualification matrix is empty") if qualification.empty?
failed_qualification = qualification.reject { |row| row.fetch(2, "").start_with?("PASS") }
abort("A2A qualification matrix is not fully PASS: #{failed_qualification.map(&:first).join(', ')}") unless
  failed_qualification.empty?

sign_off = table_rows(section(gate, "## Sign-off", "## Effet de la gate"))
sign_off.shift if sign_off.first&.first == "Rôle"
required_roles = %w[Produit Architecture Sécurité Exploitation]
abort("A2A sign-off must contain exactly #{required_roles.join(', ')}") unless sign_off.map(&:first) == required_roles

sign_off.each do |role, identity, decision, decided_at, commit, _comment|
  abort("#{role} approval identity is missing") if identity.nil? || identity.empty? || identity == "—"
  abort("#{role} decision is not APPROVED") unless decision == "APPROVED"
  begin
    parsed_time = Time.iso8601(decided_at)
    abort("#{role} approval timestamp must be UTC") unless parsed_time.utc_offset.zero? && decided_at.end_with?("Z")
  rescue ArgumentError
    abort("#{role} approval timestamp is not ISO-8601")
  end
  abort("#{role} approval targets #{commit}, expected #{candidate}") unless commit == candidate
end

abort("A2A-189 approval evidence is missing") unless approval_path.file?
approval = approval_path.read
abort("A2A-189 approval evidence does not name candidate #{candidate}") unless approval.include?(candidate)
required_roles.each do |role|
  abort("A2A-189 approval evidence does not cover #{role}") unless approval.match?(/#{Regexp.escape(role)}.*APPROVED/)
end

unless system(RbConfig.ruby, manifest_verifier.to_s, "--check")
  abort("A2A evidence manifest verification failed")
end

puts "A2A cutover APPROVED candidate=#{candidate} approvers=#{sign_off.map { |row| row[1] }.uniq.join(',')}"
