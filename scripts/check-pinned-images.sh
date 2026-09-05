#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
unpinned=()

while IFS= read -r image; do
  case "$image" in
    ai-software-factory-*|ai-factory-signoz-*|sha256:*) ;;
    *@sha256:[0-9a-f][0-9a-f]*) ;;
    *) unpinned+=("$image") ;;
  esac
done < <("${compose[@]}" config --images | sort -u)

while IFS= read -r line; do
  case "$line" in
    *'FROM ${LITELLM_IMAGE}'*) ;;
    *'@sha256:'*) ;;
    *) unpinned+=("$line") ;;
  esac
done < <(grep -R --include='*Dockerfile*' -n '^FROM ' apps infrastructure)

litellm_arg=$("${compose[@]}" config --format json | jq -r '.services.litellm.build.args.LITELLM_IMAGE')
[[ "$litellm_arg" =~ @sha256:[0-9a-f]{64}$ ]] || unpinned+=("LiteLLM build arg: $litellm_arg")

if [ "${#unpinned[@]}" -gt 0 ]; then
  printf 'Unpinned image: %s\n' "${unpinned[@]}" >&2
  exit 1
fi

echo "All external runtime and build images are pinned by digest."
