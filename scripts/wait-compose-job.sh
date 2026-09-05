#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

service=${1:?Compose service name is required}
timeout_seconds=${2:-120}

if ! [[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "Timeout must be a positive number of seconds." >&2
  exit 2
fi

compose=(docker compose --env-file .env -f infrastructure/compose.yaml)

for ((attempt = 1; attempt <= timeout_seconds; attempt++)); do
  state_json=$("${compose[@]}" ps -a --format json "$service")
  state=$(printf '%s\n' "$state_json" | jq -sr '.[0].State // empty')
  exit_code=$(printf '%s\n' "$state_json" | jq -sr '.[0].ExitCode // empty')

  if [ "$state" = "exited" ]; then
    if [ "$exit_code" = "0" ]; then
      echo "Compose job completed: $service"
      exit 0
    fi
    echo "Compose job failed: $service (exit code ${exit_code:-unknown})" >&2
    "${compose[@]}" logs --tail=100 "$service" >&2 || true
    exit 1
  fi

  sleep 1
done

echo "Compose job did not complete within ${timeout_seconds}s: $service" >&2
"${compose[@]}" ps -a "$service" >&2 || true
exit 1
