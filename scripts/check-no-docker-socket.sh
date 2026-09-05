#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

targets=(
  .env.example
  .env.delete
  Makefile
  infrastructure/compose.yaml
  apps/orchestrator/Dockerfile
  apps/mcp/sandbox-execution-server/Dockerfile
  apps/mcp/sandbox-execution-server/src/main
)

forbidden='(/var/run/docker\.sock|DOCKER_SOCKET_GID|AI_FACTORY_SANDBOX_RUNTIME=docker|DockerSandboxRuntime|docker (run|ps|inspect|rm))'

if rg -n "$forbidden" "${targets[@]}"; then
  echo "A forbidden Docker socket dependency was found in active runtime files." >&2
  exit 1
fi

echo "No active Docker socket dependency found."
