#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <backup-directory> <isolated-volume-prefix>" >&2
  exit 2
fi

backup_directory=$(cd "$1" && pwd -P)
prefix=$2
[[ "$prefix" =~ ^ai-factory-signoz-restore-[a-z0-9-]+$ ]] || {
  echo "Prefix must start with ai-factory-signoz-restore- and contain lowercase letters, digits or hyphens" >&2
  exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$backup_directory" && sha256sum -c manifest.sha256)
else
  (cd "$backup_directory" && shasum -a 256 -c manifest.sha256)
fi

archives=(
  ai-factory-signoz-metastore-postgres-0-data.tgz
  ai-factory-signoz-telemetrykeeper-0-data.tgz
  ai-factory-signoz-telemetrystore-0-0-data.tgz
  ai-factory-signoz-telemetrystore-user-scripts.tgz
)

created=()
rollback() {
  if [ "${#created[@]}" -gt 0 ]; then
    docker volume rm "${created[@]}" >/dev/null 2>&1 || true
  fi
}
trap rollback ERR

for archive in "${archives[@]}"; do
  suffix=${archive%.tgz}
  target="$prefix-$suffix"
  if docker volume inspect "$target" >/dev/null 2>&1; then
    echo "Target volume already exists: $target" >&2
    exit 2
  fi
  docker volume create "$target" >/dev/null
  created+=("$target")
  docker run --rm --network none \
    -v "$target:/target" \
    -v "$backup_directory:/backup:ro" \
    busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
    tar -xzf "/backup/$archive" -C /target
  first_entry=$(docker run --rm --network none -v "$target:/target:ro" \
    busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
    find /target -mindepth 1 -print -quit)
  [ -n "$first_entry" ] || { echo "Restored volume is empty: $target" >&2; exit 1; }
  echo "$target"
done

trap - ERR
echo "Restore completed in isolated volumes. No active SigNoz volume was modified."
