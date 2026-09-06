#!/usr/bin/env ruby
# frozen_string_literal: true

model = File.read(ARGV.fetch(0, "docs/architecture/a2a/A2A-110-threat-model.md"))
register = File.read("docs/qualification/a2a/A2A-RISK-REGISTER.md")

required = {
  "A2A-TM-01" => "Spoofing de carte",
  "A2A-TM-02" => "Confused deputy",
  "A2A-TM-03" => "Rejeu",
  "A2A-TM-04" => "SSRF",
  "A2A-TM-05" => "Élévation de privilège",
  "A2A-TM-06" => "Poisoning d'artefact",
  "A2A-TM-07" => "Cross-tenant",
  "A2A-TM-08" => "Déni de service"
}

required.each_with_index do |(id, title), index|
  abort("missing threat #{id} #{title}") unless model.include?("#{id} — #{title}")
  next_id = required.keys[index + 1]
  section = model.split("### #{id}", 2).fetch(1)
  section = section.split("### #{next_id}", 2).first if next_id
  %w[**Scénario.** **Prévention.** **Détection/réponse.** **Preuves.**].each do |marker|
    abort("#{id} lacks #{marker}") unless section.include?(marker)
  end
end

(1..20).each do |number|
  risk = format("A2A-R%02d", number)
  abort("risk register lacks #{risk}") unless register.include?(risk)
end

abort("Temporal authority is not explicit") unless model.include?("Temporal reste l'unique autorité")
abort("risk acceptance rule is absent") unless model.include?("Aucun risque\nrésiduel critique n'est acceptable")

puts "A2A threat model verified: 8 required scenarios, controls, proofs and 20 registered risks."
