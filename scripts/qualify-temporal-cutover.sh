#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

qualified_image=${QUALIFIED_ORCHESTRATOR_IMAGE_ID:-$(docker image inspect ai-factory-orchestrator --format '{{.Id}}')}
[[ "$qualified_image" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  echo "Qualified orchestrator image ID is invalid: $qualified_image" >&2
  exit 2
}

started_at=$(date +%s)
steps=0

run_step() {
  label=$1
  shift
  step_started=$(date +%s)
  echo "[cutover-qualification] START $label"
  "$@"
  step_seconds=$(( $(date +%s) - step_started ))
  steps=$((steps + 1))
  echo "[cutover-qualification] PASS $label duration_seconds=$step_seconds"
}

run_step freeze make temporal-cutover-freeze
run_step baseline make temporal-cutover-baseline
run_step unit-and-architecture make test
run_step replay make temporal-replay
run_step compose-readiness make test-temporal-compose
run_step capacity-limits make test-temporal-capacity-limits
run_step backpressure make test-temporal-backpressure
run_step network-partition ./scripts/test-temporal-network-partition.sh
run_step orchestrator-restarts make test-temporal-orchestrator-restarts
run_step worker-heartbeat make test-temporal-worker-heartbeat
run_step storage-restarts make test-temporal-storage-restarts
run_step dependency-outages make test-temporal-dependency-outages
run_step backup-restore ./scripts/test-temporal-backup-restore.sh
run_step pipeline-delivery make test-temporal-pipeline-delivery
run_step worker-version-rollback make test-temporal-human-wait-rotation
run_step compose-cycle make test-temporal-compose-cycle

deployed_image=$(docker inspect ai-factory-orchestrator-1 --format '{{.Image}}')
[ "$deployed_image" = "$qualified_image" ] || {
  echo "Deployed orchestrator image changed during qualification: $qualified_image -> $deployed_image" >&2
  exit 1
}

elapsed=$(( $(date +%s) - started_at ))
echo "Temporal cutover artifact qualified: image=$qualified_image steps=$steps duration_seconds=$elapsed"
