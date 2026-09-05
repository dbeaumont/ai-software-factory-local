#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
expected_days=${1:-7}
[[ "$expected_days" =~ ^[1-9][0-9]*$ ]] || { echo "Expected retention days must be a positive integer" >&2; exit 2; }
expected_seconds=$((expected_days * 86400))
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}

description=$(docker compose --env-file .env -f infrastructure/compose.yaml run --rm --no-deps \
  --entrypoint temporal temporal-namespace operator namespace describe --address temporal:7233 \
  --namespace "$namespace" --output json)
printf '%s' "$description" | grep -Fq "\"workflowExecutionRetentionTtl\": \"${expected_seconds}s\"" || {
  echo "Temporal namespace retention differs from ${expected_days} days" >&2
  exit 1
}

echo "Temporal namespace '$namespace' history retention verified at ${expected_days} days."
