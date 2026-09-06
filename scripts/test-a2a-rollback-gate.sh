#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
fixture=$(mktemp)
invalid=$(mktemp)
tampered=$(mktemp)
trap 'rm -f "$fixture" "$invalid" "$tampered"' EXIT

ruby -r json -r digest - "$fixture" <<'RUBY'
def canonical(value)
  case value
  when Hash then value.keys.sort.to_h { |key| [key, canonical(value.fetch(key))] }
  when Array then value.map { |item| canonical(item) }
  else value
  end
end
digest = "a" * 64
image = "registry.example/ai-factory@sha256:#{digest}"
gate = {
  "schemaVersion" => "1", "gateId" => "A2A-206", "decision" => "APPROVED",
  "incident" => {"id" => "INC-A2A-42", "openedAt" => "2026-09-06T20:00:00Z", "evidenceDigest" => digest},
  "source" => {"commit" => "1" * 40, "orchestratorImage" => image, "runtimeImage" => image,
               "temporalBuildId" => "a2a-build-current"},
  "target" => {"commit" => "2" * 40, "orchestratorImage" => image.sub("a" * 64, "b" * 64),
               "runtimeImage" => image.sub("a" * 64, "c" * 64), "temporalBuildId" => "a2a-build-rollback"},
  "taskInventory" => {"total" => 5, "states" => {"SUBMITTED" => 1, "WORKING" => 1,
    "INPUT_REQUIRED" => 1, "AUTH_REQUIRED" => 0, "COMPLETED" => 1, "REJECTED" => 0,
    "FAILED" => 0, "CANCELED" => 1}, "digest" => digest, "capturedAt" => "2026-09-06T20:01:00Z"},
  "backups" => %w[temporal a2a-task-store evidence orchestrator-projection].map do |authority|
    {"authority" => authority, "reference" => "backup://#{authority}/42", "digest" => digest,
     "status" => "VERIFIED", "verifiedAt" => "2026-09-06T20:02:00Z"}
  end,
  "reconciliation" => {"status" => "PASS", "digest" => digest, "total" => 5,
                         "lost" => 0, "duplicates" => 0, "unresolved" => 0},
  "compatibility" => {"status" => "PASS", "digest" => digest, "databaseFloor" => "V005",
                        "replayErrors" => 0, "unsupportedContracts" => 0},
  "approval" => {"identity" => "operator@example.test", "role" => "OPERATIONS", "decision" => "APPROVED",
                   "decidedAt" => "2026-09-06T20:03:00Z"}
}
gate["approval"]["boundDigest"] = Digest::SHA256.hexdigest(JSON.generate(canonical(gate)))
File.write(ARGV.fetch(0), JSON.pretty_generate(gate))
RUBY

ruby scripts/verify-a2a-rollback-gate.rb "$fixture" >/dev/null
ruby -r json - "$fixture" "$invalid" <<'RUBY'
gate = JSON.parse(File.read(ARGV.fetch(0)))
gate["reconciliation"]["unresolved"] = 1
File.write(ARGV.fetch(1), JSON.generate(gate))
RUBY
if ruby scripts/verify-a2a-rollback-gate.rb "$invalid" >/dev/null 2>&1; then
  echo "Rollback gate accepted an unresolved task" >&2
  exit 1
fi
ruby -r json - "$fixture" "$tampered" <<'RUBY'
gate = JSON.parse(File.read(ARGV.fetch(0)))
gate["incident"]["evidenceDigest"] = "f" * 64
File.write(ARGV.fetch(1), JSON.generate(gate))
RUBY
if ruby scripts/verify-a2a-rollback-gate.rb "$tampered" >/dev/null 2>&1; then
  echo "Rollback gate accepted a manifest modified after approval" >&2
  exit 1
fi

echo "A2A rollback gate verifier passed: exact release binding accepted; unresolved reconciliation rejected."
