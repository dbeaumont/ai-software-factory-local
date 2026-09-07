#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

targets=(
  .env.example
  Makefile
  infrastructure/compose.yaml
  infrastructure/a2a/compose-agents.yaml
  apps/orchestrator/Dockerfile
  apps/mcp/sandbox-execution-server/Dockerfile
  apps/mcp/sandbox-execution-server/src/main
)

forbidden='(/var/run/docker\.sock|DOCKER_SOCKET_GID|AI_FACTORY_SANDBOX_RUNTIME=docker|DockerSandboxRuntime|docker (run|ps|inspect|rm))'

for target in "${targets[@]}"; do
  [ -e "$target" ] || { echo "Docker socket audit target is missing: $target" >&2; exit 2; }
done

set +e
matches=$(rg -n "$forbidden" "${targets[@]}" 2>&1)
status=$?
set -e

case "$status" in
  0)
    printf '%s\n' "$matches" >&2
    echo "A forbidden Docker socket dependency was found in active runtime files." >&2
    exit 1
    ;;
  1) ;;
  *)
    printf '%s\n' "$matches" >&2
    echo "Docker socket audit could not inspect every active runtime file." >&2
    exit "$status"
    ;;
esac

echo "No active Docker socket dependency found."
