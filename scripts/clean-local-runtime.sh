#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

execute() {
  if [ "${AI_FACTORY_CLEAN_DRY_RUN:-false}" = true ]; then
    printf 'Would remove:'
    printf ' %q' "$@"
    printf '\n'
  else
    "$@"
  fi
}

execute_quiet() {
  if [ "${AI_FACTORY_CLEAN_DRY_RUN:-false}" = true ]; then
    execute "$@"
  else
    "$@" >/dev/null
  fi
}

is_project_resource() {
  case "$1" in
    ai-factory|ai-software-factory|ai-software-factory-local|\
    ai-factory-a2a-it-*|ai-factory-a2a-fault-*|ai-factory-a2a-perf-*) return 0 ;;
    *) return 1 ;;
  esac
}

is_standalone_container() {
  case "$1" in
    a2a-reference-*|a2a-project-server-*|a2a-tck-sut-*|\
    ai-factory-a2a-tck-*|ai-factory-test-otel-collector|ai-factory-test-otel-sink|\
    ai-factory-temporal-wait-rotation|ai-factory-temporal-restore-*|\
    ai-factory-cutover-restore-*) return 0 ;;
    *) return 1 ;;
  esac
}

is_standalone_volume() {
  case "$1" in
    ai-factory-m2|ai-factory-trivy-db|factory-workspace|\
    ai-factory-temporal-restore-*|ai-factory-cutover-restore-*|\
    ai-factory-signoz-restore-test-*) return 0 ;;
    *) return 1 ;;
  esac
}

is_standalone_network() {
  case "$1" in
    ai-factory-a2a-interop-*|ai-factory-a2a-tck-*) return 0 ;;
    *) return 1 ;;
  esac
}

echo "Removing interrupted project test and restore containers..."
while IFS='|' read -r id name project; do
  [ -n "$id" ] || continue
  if is_project_resource "$project" || is_standalone_container "$name"; then
    execute_quiet docker rm -f -v "$id"
  fi
done < <(docker ps -a --format '{{.ID}}|{{.Names}}|{{.Label "com.docker.compose.project"}}')

echo "Removing current, historical, test and restore volumes owned by this project..."
while IFS='|' read -r name project; do
  [ -n "$name" ] || continue
  if is_project_resource "$project" || is_standalone_volume "$name"; then
    execute_quiet docker volume rm "$name"
  fi
done < <(docker volume ls --format '{{.Name}}|{{.Label "com.docker.compose.project"}}')

echo "Removing interrupted project test networks..."
while IFS='|' read -r id name project; do
  [ -n "$id" ] || continue
  if is_project_resource "$project" || is_standalone_network "$name"; then
    execute_quiet docker network rm "$id"
  fi
done < <(docker network ls --format '{{.ID}}|{{.Name}}|{{.Label "com.docker.compose.project"}}')

echo "Removing repository-local build outputs and dependency caches..."
while IFS= read -r -d '' directory; do
  execute rm -rf -- "$directory"
done < <(find apps examples infrastructure scripts -type d \
  \( -name target -o -name build -o -name node_modules -o -name .gradle \
     -o -name .cache -o -name .pytest_cache -o -name .mypy_cache \
     -o -name .ruff_cache -o -name __pycache__ \) \
  -prune -print0)
