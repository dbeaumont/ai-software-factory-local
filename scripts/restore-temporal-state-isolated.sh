#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <backup-directory> <isolated-prefix>" >&2
  exit 2
fi

backup_directory=$(cd "$1" && pwd -P)
prefix=$2
[[ "$prefix" =~ ^ai-factory-temporal-restore-[a-z0-9-]+$ ]] || {
  echo "Prefix must start with ai-factory-temporal-restore- and contain lowercase letters, digits or hyphens" >&2
  exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$backup_directory" && sha256sum -c manifest.sha256)
else
  (cd "$backup_directory" && shasum -a 256 -c manifest.sha256)
fi

orchestrator_database=$(tr -d '\r\n' < "$backup_directory/orchestrator-database.txt")
[[ "$orchestrator_database" =~ ^[a-zA-Z_][a-zA-Z0-9_]{0,62}$ ]] || {
  echo "Invalid orchestrator database name in backup" >&2
  exit 2
}
expected_temporal_tables=$(tr -d '\r\n' < "$backup_directory/temporal-table-count.txt")
expected_visibility_tables=$(tr -d '\r\n' < "$backup_directory/temporal-visibility-table-count.txt")
expected_projection_tables=$(tr -d '\r\n' < "$backup_directory/orchestrator-table-count.txt")
expected_evidence_files=$(tr -d '\r\n' < "$backup_directory/evidence-file-count.txt")
expected_scm_files=$(tr -d '\r\n' < "$backup_directory/scm-delivery-file-count.txt")
for count in "$expected_temporal_tables" "$expected_visibility_tables" \
  "$expected_projection_tables" "$expected_evidence_files" "$expected_scm_files"; do
  [[ "$count" =~ ^[0-9]+$ ]] || { echo "Invalid source count in backup" >&2; exit 2; }
done

postgres_image='postgres:16-alpine@sha256:4327b9fd295502f326f44153a1045a7170ddbfffed1c3829798328556cfd09e2'
busybox_image='busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0'
temporal_volume="$prefix-temporal-db-data"
orchestrator_volume="$prefix-orchestrator-db-data"
evidence_volume="$prefix-evidence-state"
scm_volume="$prefix-scm-delivery-state"
temporal_container="$prefix-temporal-db"
orchestrator_container="$prefix-orchestrator-db"
volumes=("$scm_volume" "$evidence_volume" "$temporal_volume" "$orchestrator_volume")
containers=("$temporal_container" "$orchestrator_container")
created_volumes=()

cleanup_containers() {
  docker rm -f "${containers[@]}" >/dev/null 2>&1 || true
}
rollback() {
  cleanup_containers
  if [ "${#created_volumes[@]}" -gt 0 ]; then
    docker volume rm "${created_volumes[@]}" >/dev/null 2>&1 || true
  fi
}
trap rollback ERR

for volume in "${volumes[@]}"; do
  if docker volume inspect "$volume" >/dev/null 2>&1; then
    echo "Target volume already exists: $volume" >&2
    exit 2
  fi
  docker volume create "$volume" >/dev/null
  created_volumes+=("$volume")
done

# Restore the external-effect ledger and referenced evidence first, then Temporal, then its rebuildable projection.
docker run --rm --network none -v "$scm_volume:/target" -v "$backup_directory:/backup:ro" \
  "$busybox_image" tar -xzf /backup/scm-delivery-state.tgz -C /target
scm_files=$(docker run --rm --network none -v "$scm_volume:/target:ro" "$busybox_image" \
  sh -c 'find /target -type f | wc -l')
docker run --rm --network none -v "$evidence_volume:/target" -v "$backup_directory:/backup:ro" \
  "$busybox_image" tar -xzf /backup/evidence-state.tgz -C /target
evidence_files=$(docker run --rm --network none -v "$evidence_volume:/target:ro" "$busybox_image" \
  sh -c 'find /target -type f | wc -l')

docker run -d --name "$temporal_container" --network none \
  -e POSTGRES_USER=temporal -e POSTGRES_PASSWORD=restore-only -e POSTGRES_DB=temporal \
  -v "$temporal_volume:/var/lib/postgresql/data" -v "$backup_directory:/backup:ro" \
  "$postgres_image" >/dev/null
docker run -d --name "$orchestrator_container" --network none \
  -e POSTGRES_USER=ai_factory -e POSTGRES_PASSWORD=restore-only -e POSTGRES_DB="$orchestrator_database" \
  -v "$orchestrator_volume:/var/lib/postgresql/data" -v "$backup_directory:/backup:ro" \
  "$postgres_image" >/dev/null

for container in "${containers[@]}"; do
  for attempt in $(seq 1 60); do
    if docker exec "$container" pg_isready -U "$( [ "$container" = "$temporal_container" ] && printf temporal || printf ai_factory )" >/dev/null 2>&1; then
      break
    fi
    [ "$attempt" -lt 60 ] || { echo "PostgreSQL restore container did not become ready: $container" >&2; exit 1; }
    sleep 1
  done
done

docker exec "$temporal_container" createdb -U temporal temporal_visibility
# PostgreSQL roles are cluster-wide objects and are not part of a per-database pg_dump.
# Recreate the schema-owned read-only principal before restoring ACL statements.
docker exec "$orchestrator_container" createuser -U ai_factory --no-login ai_factory_ui_reader
docker exec "$temporal_container" pg_restore --exit-on-error --no-owner -U temporal -d temporal \
  /backup/temporal.dump
docker exec "$temporal_container" pg_restore --exit-on-error --no-owner -U temporal -d temporal_visibility \
  /backup/temporal-visibility.dump
docker exec "$orchestrator_container" pg_restore --exit-on-error --no-owner -U ai_factory \
  -d "$orchestrator_database" /backup/orchestrator.dump

temporal_tables=$(docker exec "$temporal_container" psql -U temporal -d temporal -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')")
visibility_tables=$(docker exec "$temporal_container" psql -U temporal -d temporal_visibility -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')")
projection_tables=$(docker exec "$orchestrator_container" psql -U ai_factory -d "$orchestrator_database" -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')")
[ "$temporal_tables" = "$expected_temporal_tables" ] \
  && [ "$visibility_tables" = "$expected_visibility_tables" ] \
  && [ "$projection_tables" = "$expected_projection_tables" ] \
  && [ "$evidence_files" = "$expected_evidence_files" ] \
  && [ "$scm_files" = "$expected_scm_files" ] || {
  echo "Restored state counts differ from the coherent source snapshot" >&2
  exit 1
}

cleanup_containers
trap - ERR
printf '%s\n' "SCM idempotency restored: $scm_volume ($scm_files files)" \
  "Evidence restored: $evidence_volume ($evidence_files files)" \
  "Temporal restored: $temporal_volume ($temporal_tables + $visibility_tables tables)" \
  "Projection restored: $orchestrator_volume ($projection_tables tables)" \
  "No active volume was modified."
