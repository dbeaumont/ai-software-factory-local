#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$#" -ne 2 ]; then
  echo "Usage: $0 <backup-directory> <isolated-prefix>" >&2
  exit 2
fi

backup_directory=$(cd "$1" && pwd -P)
prefix=$2
[[ "$prefix" =~ ^ai-factory-cutover-restore-[a-z0-9-]+$ ]] || {
  echo "Prefix must start with ai-factory-cutover-restore- and contain lowercase letters, digits or hyphens" >&2
  exit 2
}

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$backup_directory" && sha256sum -c manifest.sha256)
else
  (cd "$backup_directory" && shasum -a 256 -c manifest.sha256)
fi

for count_file in gitea-table-count.txt gitea-file-count.txt workspace-file-count.txt configuration-entry-count.txt; do
  count=$(tr -d '\r\n ' < "$backup_directory/$count_file")
  [[ "$count" =~ ^[0-9]+$ ]] || { echo "Invalid count in $count_file" >&2; exit 2; }
done

core_prefix="ai-factory-temporal-restore-${prefix#ai-factory-cutover-restore-}-core"
./scripts/restore-temporal-state-isolated.sh "$backup_directory/core" "$core_prefix"

postgres_image='postgres:16-alpine@sha256:4327b9fd295502f326f44153a1045a7170ddbfffed1c3829798328556cfd09e2'
busybox_image='busybox:1.37@sha256:9db7b59979c38555a39def84a31fb98b5296952f9e3afd4f6f11f05b07adfab0'
gitea_db_volume="$prefix-gitea-db-data"
gitea_data_volume="$prefix-gitea-data"
workspace_volume="$prefix-factory-workspace"
gitea_db_container="$prefix-gitea-db"
projection_container="$prefix-projection-db"
configuration_directory=$(mktemp -d /private/tmp/ai-factory-cutover-config-restore-XXXXXX)

cleanup_containers() {
  docker rm -f "$gitea_db_container" "$projection_container" >/dev/null 2>&1 || true
}
cleanup_on_error() {
  cleanup_containers
  docker volume rm "$gitea_db_volume" "$gitea_data_volume" "$workspace_volume" >/dev/null 2>&1 || true
  rm -rf "$configuration_directory"
}
trap cleanup_on_error ERR

for volume in "$gitea_db_volume" "$gitea_data_volume" "$workspace_volume"; do
  docker volume inspect "$volume" >/dev/null 2>&1 && {
    echo "Target volume already exists: $volume" >&2
    exit 2
  }
  docker volume create "$volume" >/dev/null
done

docker run --rm --network none -v "$gitea_data_volume:/target" -v "$backup_directory:/backup:ro" \
  "$busybox_image" tar -xzf /backup/gitea-data.tgz -C /target
docker run --rm --network none -v "$workspace_volume:/target" -v "$backup_directory:/backup:ro" \
  "$busybox_image" tar -xzf /backup/factory-workspace.tgz -C /target
tar -xzf "$backup_directory/configuration.tgz" -C "$configuration_directory"

docker run -d --name "$gitea_db_container" --network none \
  -e POSTGRES_USER=gitea -e POSTGRES_PASSWORD=restore-only -e POSTGRES_DB=gitea \
  -v "$gitea_db_volume:/var/lib/postgresql/data" -v "$backup_directory:/backup:ro" \
  "$postgres_image" >/dev/null

for attempt in $(seq 1 60); do
  docker exec "$gitea_db_container" pg_isready -U gitea >/dev/null 2>&1 && break
  [ "$attempt" -lt 60 ] || { echo "Gitea restore database did not become ready" >&2; exit 1; }
  sleep 1
done
docker exec "$gitea_db_container" pg_restore --exit-on-error --no-owner -U gitea -d gitea /backup/gitea.dump

expected_gitea_tables=$(tr -d '\r\n ' < "$backup_directory/gitea-table-count.txt")
expected_gitea_files=$(tr -d '\r\n ' < "$backup_directory/gitea-file-count.txt")
expected_workspace_files=$(tr -d '\r\n ' < "$backup_directory/workspace-file-count.txt")
expected_configuration_entries=$(tr -d '\r\n ' < "$backup_directory/configuration-entry-count.txt")
gitea_tables=$(docker exec "$gitea_db_container" psql -U gitea -d gitea -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')")
gitea_files=$(docker run --rm --network none -v "$gitea_data_volume:/target:ro" "$busybox_image" \
  sh -c 'find /target -type f | wc -l')
workspace_files=$(docker run --rm --network none -v "$workspace_volume:/target:ro" "$busybox_image" \
  sh -c 'find /target -type f | wc -l')
configuration_entries=$(tar -tzf "$backup_directory/configuration.tgz" | wc -l | tr -d ' ')

[ "$gitea_tables" = "$expected_gitea_tables" ] \
  && [ "$gitea_files" = "$expected_gitea_files" ] \
  && [ "$workspace_files" = "$expected_workspace_files" ] \
  && [ "$configuration_entries" = "$expected_configuration_entries" ] || {
  echo "Restored cutover authority counts differ from the source snapshot" >&2
  exit 1
}
[ -f "$configuration_directory/.env" ] && [ -f "$configuration_directory/infrastructure/compose.yaml" ] || {
  echo "Restored configuration is incomplete" >&2
  exit 1
}

core_projection_volume="$core_prefix-orchestrator-db-data"
core_projection_database=$(tr -d '\r\n' < "$backup_directory/core/orchestrator-database.txt")
docker run -d --name "$projection_container" --network none \
  -e POSTGRES_USER=ai_factory -e POSTGRES_PASSWORD=restore-only -e POSTGRES_DB="$core_projection_database" \
  -v "$core_projection_volume:/var/lib/postgresql/data" "$postgres_image" >/dev/null
for attempt in $(seq 1 60); do
  docker exec "$projection_container" pg_isready -U ai_factory >/dev/null 2>&1 && break
  [ "$attempt" -lt 60 ] || { echo "Projection restore database did not become ready" >&2; exit 1; }
  sleep 1
done
admission_state=$(docker exec "$projection_container" psql -U ai_factory -d "$core_projection_database" -Atc \
  "select admissions_open || '|' || reason || '|' || revision from factory_admission_control where control_key = 'global'")
[[ "$admission_state" == false\|temporal_cutover\|* ]] || {
  echo "Restored admission gate is not closed for the Temporal cutover" >&2
  exit 1
}

cleanup_containers
rm -rf "$configuration_directory"
trap - ERR
printf '%s\n' \
  "Gitea restored: $gitea_db_volume ($gitea_tables tables), $gitea_data_volume ($gitea_files files)" \
  "Workspaces restored: $workspace_volume ($workspace_files files)" \
  "Configuration restored and validated in an isolated temporary directory ($configuration_entries entries)" \
  "Core authorities restored by $core_prefix; admission state: $admission_state" \
  "No active volume was modified."
