#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
repository=$PWD

if [ "$#" -ne 1 ]; then
  echo "Usage: $0 <empty-backup-directory>" >&2
  exit 2
fi

destination=$1
case "$destination" in
  /|"$PWD"|"$PWD"/.) echo "Refusing broad backup destination: $destination" >&2; exit 2 ;;
esac

mkdir -p "$destination"
[ -z "$(find "$destination" -mindepth 1 -maxdepth 1 -print -quit)" ] || {
  echo "Backup destination must be empty: $destination" >&2
  exit 2
}
destination=$(cd "$destination" && pwd -P)

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
writers=(ingester ai-factory-signoz-signoz-0 ai-factory-signoz-telemetrystore-clickhouse-0-0 ai-factory-signoz-metastore-postgres-0 ai-factory-signoz-telemetrykeeper-clickhousekeeper-0)
volumes=(
  ai-factory-signoz-metastore-postgres-0-data
  ai-factory-signoz-telemetrykeeper-0-data
  ai-factory-signoz-telemetrystore-0-0-data
  ai-factory-signoz-telemetrystore-user-scripts
)

restart_backend() {
  "${compose[@]}" start ai-factory-signoz-metastore-postgres-0 ai-factory-signoz-telemetrykeeper-clickhousekeeper-0 >/dev/null 2>&1 || true
  "${compose[@]}" start ai-factory-signoz-telemetrystore-clickhouse-0-0 ai-factory-signoz-signoz-0 ingester >/dev/null 2>&1 || true
}
trap restart_backend EXIT

"${compose[@]}" stop "${writers[@]}" >/dev/null

for volume in "${volumes[@]}"; do
  docker volume inspect "$volume" >/dev/null
  docker run --rm --network none \
    -v "$volume:/source:ro" \
    -v "$destination:/backup" \
    busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
    tar -czf "/backup/$volume.tgz" -C /source .
done

(
  cd "$destination"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum ./*.tgz > manifest.sha256
  else
    shasum -a 256 ./*.tgz > manifest.sha256
  fi
  git -C "$repository" rev-parse HEAD > source-commit.txt
  date -u +%FT%TZ > created-at.txt
)

restart_backend
trap - EXIT
echo "SigNoz backup created in $destination"
