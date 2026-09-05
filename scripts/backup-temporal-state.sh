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
writers=(orchestrator temporal evidence-mcp scm-delivery-mcp)

restart_writers() {
  "${compose[@]}" up -d evidence-mcp scm-delivery-mcp temporal orchestrator >/dev/null 2>&1 || true
}
trap restart_writers EXIT

# Freeze every writer before taking any component snapshot. Databases remain online only for pg_dump.
"${compose[@]}" stop "${writers[@]}" >/dev/null

"${compose[@]}" exec -T temporal-db pg_dump -U temporal -d temporal -Fc > "$destination/temporal.dump"
"${compose[@]}" exec -T temporal-db pg_dump -U temporal -d temporal_visibility -Fc \
  > "$destination/temporal-visibility.dump"
"${compose[@]}" exec -T temporal-db psql -U temporal -d temporal -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')" \
  > "$destination/temporal-table-count.txt"
"${compose[@]}" exec -T temporal-db psql -U temporal -d temporal_visibility -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')" \
  > "$destination/temporal-visibility-table-count.txt"
"${compose[@]}" exec -T orchestrator-db sh -c \
  'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$destination/orchestrator.dump"
"${compose[@]}" exec -T orchestrator-db sh -c 'printf "%s\n" "$POSTGRES_DB"' \
  > "$destination/orchestrator-database.txt"
"${compose[@]}" exec -T orchestrator-db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from pg_catalog.pg_tables where schemaname not in ('"'"'pg_catalog'"'"','"'"'information_schema'"'"')"' \
  > "$destination/orchestrator-table-count.txt"

evidence_container=$("${compose[@]}" ps -aq evidence-mcp)
[ -n "$evidence_container" ] || { echo "Evidence MCP container is missing" >&2; exit 1; }
docker run --rm --network none --volumes-from "$evidence_container:ro" \
  -v "$destination:/backup" \
  busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
  tar -czf /backup/evidence-state.tgz -C /var/lib/ai-factory/evidence .
docker run --rm --network none --volumes-from "$evidence_container:ro" \
  busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
  sh -c 'find /var/lib/ai-factory/evidence -type f | wc -l' > "$destination/evidence-file-count.txt"

scm_container=$("${compose[@]}" ps -aq scm-delivery-mcp)
[ -n "$scm_container" ] || { echo "SCM delivery MCP container is missing" >&2; exit 1; }
docker run --rm --network none --volumes-from "$scm_container:ro" \
  -v "$destination:/backup" \
  busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
  tar -czf /backup/scm-delivery-state.tgz -C /var/lib/ai-factory/scm .
docker run --rm --network none --volumes-from "$scm_container:ro" \
  busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0 \
  sh -c 'find /var/lib/ai-factory/scm -type f | wc -l' > "$destination/scm-delivery-file-count.txt"

(
  cd "$destination"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum temporal.dump temporal-visibility.dump orchestrator.dump evidence-state.tgz scm-delivery-state.tgz \
      orchestrator-database.txt temporal-table-count.txt temporal-visibility-table-count.txt \
      orchestrator-table-count.txt evidence-file-count.txt scm-delivery-file-count.txt > manifest.sha256
  else
    shasum -a 256 temporal.dump temporal-visibility.dump orchestrator.dump evidence-state.tgz scm-delivery-state.tgz \
      orchestrator-database.txt temporal-table-count.txt temporal-visibility-table-count.txt \
      orchestrator-table-count.txt evidence-file-count.txt scm-delivery-file-count.txt > manifest.sha256
  fi
  git -C "$repository" rev-parse HEAD > source-commit.txt
  date -u +%FT%TZ > created-at.txt
)

restart_writers
trap - EXIT
echo "Temporal authority, Evidence and projection backup created in $destination"
