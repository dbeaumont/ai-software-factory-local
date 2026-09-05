#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
backup_directory=$(mktemp -d /private/tmp/ai-factory-temporal-backup-test-XXXXXX)
prefix="ai-factory-temporal-restore-test-$(date +%s)"
volumes=(
  "$prefix-scm-delivery-state"
  "$prefix-evidence-state"
  "$prefix-temporal-db-data"
  "$prefix-orchestrator-db-data"
)

cleanup() {
  docker rm -f "$prefix-temporal-db" "$prefix-orchestrator-db" >/dev/null 2>&1 || true
  docker volume rm "${volumes[@]}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

./scripts/backup-temporal-state.sh "$backup_directory"
./scripts/restore-temporal-state-isolated.sh "$backup_directory" "$prefix"

for volume in "${volumes[@]}"; do
  docker volume inspect "$volume" >/dev/null
done

echo "Temporal state backup and isolated restore verified. Backup retained at $backup_directory"
