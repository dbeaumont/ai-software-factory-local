#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
fixture='{"routingConfig":{"currentVersionBuildID":"current","rampingVersionBuildID":"ramping"},"versionSummaries":[{"BuildID":"current","drainageStatus":"unspecified"},{"BuildID":"ramping","drainageStatus":"draining"},{"BuildID":"old-open","drainageStatus":"draining"},{"BuildID":"old-drained","drainageStatus":"drained"}]}'

printf '%s' "$fixture" | ruby scripts/verify-a2a-worker-drainage.rb old-drained >/dev/null
for forbidden in current ramping old-open absent; do
  if printf '%s' "$fixture" | ruby scripts/verify-a2a-worker-drainage.rb "$forbidden" >/dev/null 2>&1; then
    echo "Drainage gate accepted forbidden retirement: $forbidden" >&2
    exit 1
  fi
done

echo "A2A worker drainage gate passed: current, ramping, open and absent builds are protected."
