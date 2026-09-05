#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
backup_directory=$(mktemp -d /private/tmp/ai-factory-signoz-backup-test-XXXXXX)
prefix="ai-factory-signoz-restore-test-$(date +%s)"
volumes=(
  "$prefix-ai-factory-signoz-metastore-postgres-0-data"
  "$prefix-ai-factory-signoz-telemetrykeeper-0-data"
  "$prefix-ai-factory-signoz-telemetrystore-0-0-data"
  "$prefix-ai-factory-signoz-telemetrystore-user-scripts"
)

cleanup() {
  docker volume rm "${volumes[@]}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

./scripts/backup-signoz.sh "$backup_directory"
./scripts/restore-signoz-backup-isolated.sh "$backup_directory" "$prefix"

for volume in "${volumes[@]}"; do
  docker volume inspect "$volume" >/dev/null
done

./scripts/check-signoz-telemetry.sh >/dev/null
echo "SigNoz backup and isolated restore verified. Backup retained at $backup_directory"
