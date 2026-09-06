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
a2a_agents=(
  a2a-supervisor a2a-architecture-agent a2a-impact-analysis a2a-dependencies-contracts
  a2a-code-agent a2a-developer a2a-patch-repair a2a-test-agent a2a-test-design
  a2a-test-evidence a2a-security-agent a2a-threat-model a2a-security-findings
  a2a-independent-reviewer
)
writers=(gitea orchestrator temporal evidence-mcp scm-delivery-mcp sandbox-execution-mcp "${a2a_agents[@]}")

restart_writers() {
  "${compose[@]}" --profile a2a-full up -d --wait --wait-timeout 180 "${writers[@]}" >/dev/null 2>&1 || true
}
trap restart_writers EXIT

# Freeze every writer before taking the cross-authority snapshot. Databases remain online for pg_dump.
"${compose[@]}" stop "${writers[@]}" >/dev/null

# The qualified core snapshot covers Temporal, projections, Evidence and the external-effect ledger.
# Its local restart is disabled so the complete cutover snapshot stays at one quiescent point.
AI_FACTORY_BACKUP_RESTART_WRITERS=false ./scripts/backup-temporal-state.sh "$destination/core"

"${compose[@]}" exec -T a2a-task-db pg_dump -U ai_factory_a2a -d ai_factory_a2a -Fc \
  > "$destination/a2a-task.dump"
"${compose[@]}" exec -T a2a-task-db psql -U ai_factory_a2a -d ai_factory_a2a -Atc \
  "select count(*) from pg_catalog.pg_tables where schemaname not in ('pg_catalog','information_schema')" \
  > "$destination/a2a-task-table-count.txt"

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

configuration=(.env infrastructure resources/temporal resources/agents resources/prompts resources/a2a)
[ ! -f .vault ] || configuration+=(.vault)
[ ! -d .local/a2a-pki ] || configuration+=(.local/a2a-pki)
[ ! -d .local/a2a-secrets ] || configuration+=(.local/a2a-secrets)
tar -czf "$destination/configuration.tgz" "${configuration[@]}"
tar -tzf "$destination/configuration.tgz" | wc -l | tr -d ' ' > "$destination/configuration-entry-count.txt"

: > "$destination/worker-images.ndjson"
for service in orchestrator "${a2a_agents[@]}"; do
  container=$("${compose[@]}" ps -aq "$service")
  [ -n "$container" ] || { echo "Worker container is missing: $service" >&2; exit 1; }
  image_id=$(docker inspect --format '{{.Image}}' "$container")
  image_ref=$(docker inspect --format '{{.Config.Image}}' "$container")
  printf '{"service":"%s","image_ref":"%s","image_id":"%s"}\n' \
    "$service" "$image_ref" "$image_id" >> "$destination/worker-images.ndjson"
done
"${compose[@]}" run --rm --no-deps --entrypoint /bin/sh orchestrator -c \
  'printf "%s\n" "$AI_FACTORY_TEMPORAL_BUILD_ID"' > "$destination/temporal-build-id.txt"

git -C "$repository" rev-parse HEAD > "$destination/source-commit.txt"
date -u +%FT%TZ > "$destination/created-at.txt"

(
  cd "$destination"
  files=(a2a-task.dump gitea.dump gitea-data.tgz factory-workspace.tgz configuration.tgz
    a2a-task-table-count.txt gitea-table-count.txt gitea-file-count.txt workspace-file-count.txt
    configuration-entry-count.txt worker-images.ndjson temporal-build-id.txt source-commit.txt created-at.txt)
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${files[@]}" core/* > manifest.sha256
  else
    shasum -a 256 "${files[@]}" core/* > manifest.sha256
  fi
)

restart_writers
trap - EXIT
echo "Temporal cutover authorities backup created in $destination"
