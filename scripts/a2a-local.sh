#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
compose=(docker compose --env-file .env -f infrastructure/compose.yaml)
all_roles=(
  supervisor architecture-agent impact-analysis dependencies-contracts code-agent developer patch-repair
  test-agent test-design test-evidence security-agent threat-model security-findings independent-reviewer
)
read -r -a roles <<< "${A2A_ROLES:-${all_roles[*]}}"

services() {
  local role
  for role in "${roles[@]}"; do printf 'a2a-%s\n' "$role"; done
}

cards() {
  local role service payload orchestrator caller
  orchestrator=$("${compose[@]}" ps -q orchestrator)
  caller=self
  if test -n "$orchestrator" && test "$(docker inspect --format '{{.State.Running}}' "$orchestrator")" = true; then
    caller=orchestrator
  fi
  for role in "${roles[@]}"; do
    service="a2a-$role"
    if test "$caller" = orchestrator; then
      payload=$("${compose[@]}" exec -T orchestrator curl --fail --silent --show-error \
        --cacert /var/run/ai-factory/a2a/ca.crt --cert /var/run/ai-factory/a2a/tls.crt \
        --key /var/run/ai-factory/a2a/tls.key "https://$service:8090/.well-known/agent-card.json")
    else
      payload=$("${compose[@]}" exec -T "$service" curl --fail --silent --show-error --insecure \
        --cert /var/run/ai-factory/a2a/tls.crt --key /var/run/ai-factory/a2a/tls.key \
        https://127.0.0.1:8090/.well-known/agent-card.json)
    fi
    ROLE="$role" ruby -rjson -e '
      card = JSON.parse(STDIN.read)
      abort "Agent Card role mismatch" unless card.dig("metadata", "role") == ENV.fetch("ROLE")
      abort "Agent Card is unsigned" unless card.fetch("signatures", []).any?
    ' <<< "$payload"
    printf '%-28s %s\n' "$service" "card=VALID caller=$caller"
  done
}

case "${1:-}" in
  config)
    "${compose[@]}" config --quiet
    ruby scripts/verify-a2a-compose-runtime.rb
    ruby scripts/verify-a2a-compose-network.rb
    ruby scripts/verify-a2a-mcp-networks.rb
    ;;
  status)
    selected=()
    for role in "${roles[@]}"; do selected+=("a2a-$role"); done
    "${compose[@]}" ps a2a-identity a2a-task-db "${selected[@]}"
    ;;
  cards)
    cards
    ;;
  smoke)
    selected=()
    for role in "${roles[@]}"; do selected+=("a2a-$role"); done
    local_services=(a2a-identity a2a-task-db "${selected[@]}")
    for service in "${local_services[@]}"; do
      container=$("${compose[@]}" ps -q "$service")
      test -n "$container" || { echo "$service is not running" >&2; exit 1; }
      health=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")
      test "$health" = healthy || { echo "$service is not healthy: $health" >&2; exit 1; }
    done
    "${compose[@]}" exec -T a2a-identity python /opt/a2a-identity/smoke.py
    cards
    echo "A2A local smoke passed for ${#roles[@]} role(s)."
    ;;
  logs)
    selected=()
    for role in "${roles[@]}"; do selected+=("a2a-$role"); done
    "${compose[@]}" logs --tail="${A2A_LOG_TAIL:-200}" -f a2a-task-db "${selected[@]}"
    ;;
  *)
    echo "usage: a2a-local.sh {config|status|cards|smoke|logs}" >&2
    exit 2
    ;;
esac
