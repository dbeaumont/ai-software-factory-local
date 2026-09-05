#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[ -f .env ] && set -a && source .env && set +a

base_url=${SIGNOZ_BASE_URL:-http://127.0.0.1:${SIGNOZ_PORT:-3301}}
email=${SIGNOZ_ROOT_EMAIL:-admin@ai-factory.local}
: "${SIGNOZ_ROOT_PASSWORD:?SIGNOZ_ROOT_PASSWORD must be initialized by make init}"
generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
report_path=${1:-docs/mcp/baselines/MCP-shadow-$(date -u +%Y%m%d-%H%M%S).md}

context=$(curl -fsS --get "$base_url/api/v2/sessions/context" --data-urlencode "email=$email" --data-urlencode "ref=$base_url")
org_id=$(printf '%s' "$context" | jq -er '.data.orgs[0].id')
login=$(jq -nc --arg email "$email" --arg password "$SIGNOZ_ROOT_PASSWORD" --arg orgId "$org_id" '{email:$email,password:$password,orgId:$orgId}')
session=$(curl -fsS -X POST "$base_url/api/v2/sessions/email_password" -H 'Content-Type: application/json' --data "$login")
token=$(printf '%s' "$session" | jq -er '.data.accessToken')
trap 'curl -fsS -X DELETE "$base_url/api/v2/sessions" -H "Authorization: Bearer $token" >/dev/null || true' EXIT

now=$(date +%s)
start=$(((now - 86400) * 1000))
end=$((now * 1000))

query_scalar() {
  local query=$1 payload response
  payload=$(jq -nc --arg query "$query" --argjson start "$start" --argjson end "$end" \
    '{schemaVersion:"v1",start:$start,end:$end,requestType:"time_series",compositeQuery:{queries:[{type:"promql",spec:{name:"A",query:$query,step:60}}]}}')
  response=$(curl -fsS -X POST "$base_url/api/v5/query_range" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' --data "$payload")
  printf '%s' "$response" | jq -er '
    if .status != "success" or .error != null then error(.error // "query failed")
    else ([.data.data.results[]?.aggregations[]?.series[]?.values[]?.value] | last // 0)
    end'
}

context_success=$(query_scalar 'sum(ai_factory_mcp_context_shadow_runs{outcome="success"})')
context_failure=$(query_scalar 'sum(ai_factory_mcp_context_shadow_runs{outcome="failure"})')
coverage_mean=$(query_scalar 'sum({__name__="ai_factory_mcp_context_shadow_file_coverage_ratio.sum"}) / clamp_min(sum({__name__="ai_factory_mcp_context_shadow_file_coverage_ratio.count"}), 1)')
citation_mean=$(query_scalar 'sum({__name__="ai_factory_mcp_context_shadow_citation_validity_ratio.sum"}) / clamp_min(sum({__name__="ai_factory_mcp_context_shadow_citation_validity_ratio.count"}), 1)')
context_mcp_latency_mean=$(query_scalar 'sum({__name__="ai_factory_mcp_client_duration.sum"}) / clamp_min(sum({__name__="ai_factory_mcp_client_duration.count"}), 1)')
direct_chars=$(query_scalar 'sum({__name__="ai_factory_mcp_context_shadow_chars.sum",source="direct"})')
mcp_chars=$(query_scalar 'sum({__name__="ai_factory_mcp_context_shadow_chars.sum",source="mcp"})')
sandbox_success=$(query_scalar 'sum(ai_factory_mcp_sandbox_shadow_runs{outcome="success"})')
sandbox_failure=$(query_scalar 'sum(ai_factory_mcp_sandbox_shadow_runs{outcome=~"direct_failure|mcp_failure"})')
sandbox_equal=$(query_scalar 'sum(ai_factory_mcp_sandbox_shadow_comparisons{result="equal"})')
sandbox_different=$(query_scalar 'sum(ai_factory_mcp_sandbox_shadow_comparisons{result="different"})')

mkdir -p "$(dirname "$report_path")"
{
  printf '# Rapport de campagne MCP shadow\n\n'
  printf -- '- Généré à : `%s`\n' "$generated_at"
  printf -- '- Source : `%s` (API SigNoz v5)\n' "$base_url"
  printf -- '- Fenêtre : dernières 24 heures\n'
  printf -- '- Autorité de décision : chemin direct\n\n'
  printf '## Contexte dépôt\n\n'
  printf '| Mesure | Valeur |\n|---|---:|\n'
  printf '| Runs shadow réussis | %s |\n' "$context_success"
  printf '| Runs shadow en échec | %s |\n' "$context_failure"
  printf '| Couverture moyenne des fichiers | %s |\n' "$coverage_mean"
  printf '| Validité moyenne des citations | %s |\n' "$citation_mean"
  printf '| Latence MCP Context moyenne (secondes) | %s |\n' "$context_mcp_latency_mean"
  printf '| Caractères directs cumulés | %s |\n' "$direct_chars"
  printf '| Caractères MCP cumulés | %s |\n\n' "$mcp_chars"
  printf '## Sandbox\n\n'
  printf '| Mesure | Valeur |\n|---|---:|\n'
  printf '| Comparaisons réussies | %s |\n' "$sandbox_success"
  printf '| Échecs direct/MCP | %s |\n' "$sandbox_failure"
  printf '| Sorties exactement égales | %s |\n' "$sandbox_equal"
  printf '| Sorties différentes | %s |\n\n' "$sandbox_different"
  printf '## Décision de gate\n\n'
  printf -- '- [ ] Au moins 20 tâches représentatives ont terminé en shadow.\n'
  printf -- '- [ ] La couverture des fichiers utiles est supérieure ou égale à 90 %%.\n'
  printf -- '- [ ] Toutes les citations sont vérifiables et liées au bon commit.\n'
  printf -- '- [ ] Le contexte MCP réduit les caractères/tokens sans régression du plan évaluée manuellement.\n'
  printf -- '- [ ] `validate_patch`, tests, qualité et sécurité ont chacun une comparaison sandbox réussie.\n'
  printf -- '- [ ] Les divergences sandbox sont expliquées et acceptées, ou corrigées.\n'
  printf -- '- [ ] Le Product Owner et le représentant RSSI autorisent la phase `MCP_ACTIVE`.\n'
} >"$report_path"

printf 'Rapport MCP shadow généré : %s\n' "$report_path"
