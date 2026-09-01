#!/usr/bin/env bash
set -euo pipefail

env_file="${1:-.env}"
image_tag="${2:-ai-factory-sandbox:local}"

if [[ ! -f "$env_file" ]]; then
  echo "Missing environment file: $env_file" >&2
  exit 1
fi

image_id="$(docker image inspect "$image_tag" --format '{{.Id}}')"
if [[ ! "$image_id" =~ ^sha256:[0-9a-f]{64}$ ]]; then
  echo "Docker returned an invalid immutable image ID for $image_tag" >&2
  exit 1
fi

temporary_file="$(mktemp "${env_file}.tmp.XXXXXX")"
trap 'rm -f "$temporary_file"' EXIT

awk -v image_id="$image_id" '
  BEGIN { replaced = 0 }
  /^AI_FACTORY_SANDBOX_IMAGE=/ {
    print "AI_FACTORY_SANDBOX_IMAGE=" image_id
    replaced = 1
    next
  }
  { print }
  END {
    if (!replaced) {
      print "AI_FACTORY_SANDBOX_IMAGE=" image_id
    }
  }
' "$env_file" > "$temporary_file"

mv "$temporary_file" "$env_file"
trap - EXIT
echo "Sandbox image pinned to $image_id"
