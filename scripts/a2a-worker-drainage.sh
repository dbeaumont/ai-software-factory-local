#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
build_id=${1:?usage: a2a-worker-drainage.sh BUILD_ID}
namespace=${AI_FACTORY_TEMPORAL_NAMESPACE:-ai-factory-local}
deployment=${AI_FACTORY_TEMPORAL_DEPLOYMENT_NAME:-ai-factory-orchestrator}
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)

"${compose[@]}" run --rm --no-deps --entrypoint temporal temporal-namespace \
  worker deployment describe --address temporal:7233 --namespace "$namespace" \
  --name "$deployment" --output json \
  | ruby scripts/verify-a2a-worker-drainage.rb "$build_id"
