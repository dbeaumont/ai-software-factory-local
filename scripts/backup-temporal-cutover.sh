#!/usr/bin/env bash
set -euo pipefail
umask 077

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
mkdir "$destination/core"

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
writers=(gitea orchestrator sandbox-execution-mcp)

restart_writers() {
  "${compose[@]}" up -d --wait --wait-timeout 180 "${writers[@]}" >/dev/null 2>&1 || true
}
trap restart_writers EXIT

# The qualified core snapshot freezes Temporal, projection, Evidence and the external-effect ledger.
./scripts/backup-temporal-state.sh "$destination/core"

# Admissions are already closed and Temporal is drained. Stop remaining writers for Gitea and workspace snapshots.
"${compose[@]}" stop "${writers[@]}" >/dev/null

"${compose[@]}" exec -T gitea-db pg_dump -U gitea -d gitea -Fc > "$destination/gitea.dump"
"${compose[@]}" exec -T gitea-db psql -U gitea -d gitea -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')" \
  > "$destination/gitea-table-count.txt"

gitea_container=$("${compose[@]}" ps -aq gitea)
orchestrator_container=$("${compose[@]}" ps -aq orchestrator)
[ -n "$gitea_container" ] || { echo "Gitea container is missing" >&2; exit 1; }
[ -n "$orchestrator_container" ] || { echo "Orchestrator container is missing" >&2; exit 1; }

busybox='busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0'
docker run --rm --network none --volumes-from "$gitea_container:ro" -v "$destination:/backup" \
  "$busybox" tar -czf /backup/gitea-data.tgz -C /data .
docker run --rm --network none --volumes-from "$gitea_container:ro" "$busybox" \
  sh -c 'find /data -type f | wc -l' > "$destination/gitea-file-count.txt"
docker run --rm --network none --volumes-from "$orchestrator_container:ro" -v "$destination:/backup" \
  "$busybox" tar -czf /backup/factory-workspace.tgz -C /workspace/tasks .
docker run --rm --network none --volumes-from "$orchestrator_container:ro" "$busybox" \
  sh -c 'find /workspace/tasks -type f | wc -l' > "$destination/workspace-file-count.txt"

configuration=(.env infrastructure resources/temporal resources/agents resources/prompts)
[ ! -f .vault ] || configuration+=(.vault)
tar -czf "$destination/configuration.tgz" "${configuration[@]}"
tar -tzf "$destination/configuration.tgz" | wc -l | tr -d ' ' > "$destination/configuration-entry-count.txt"

git -C "$repository" rev-parse HEAD > "$destination/source-commit.txt"
date -u +%FT%TZ > "$destination/created-at.txt"

(
  cd "$destination"
  files=(gitea.dump gitea-data.tgz factory-workspace.tgz configuration.tgz
    gitea-table-count.txt gitea-file-count.txt workspace-file-count.txt
    configuration-entry-count.txt source-commit.txt created-at.txt)
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${files[@]}" core/* > manifest.sha256
  else
    shasum -a 256 "${files[@]}" core/* > manifest.sha256
  fi
)

restart_writers
trap - EXIT
echo "Temporal cutover authorities backup created in $destination"
