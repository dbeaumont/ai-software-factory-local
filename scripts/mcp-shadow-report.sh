#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

orchestrator_url="${AI_FACTORY_REPORT_ORCHESTRATOR_URL:-http://localhost:${ORCHESTRATOR_PORT:-8080}}"
generated_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
report_path="${1:-docs/mcp/baselines/MCP-shadow-$(date -u +%Y%m%d-%H%M%S).md}"
metrics_file="$(mktemp)"
trap 'rm -f "$metrics_file"' EXIT

curl -fsS --max-time 15 "${orchestrator_url}/actuator/prometheus" -o "$metrics_file"

metric_value() {
  local expression="$1"
  awk -v expression="$expression" '$0 ~ expression { print $NF; exit }' "$metrics_file"
}

metric_sum() {
  local expression="$1"
  awk -v expression="$expression" '$0 ~ expression { sum += $NF } END { print sum + 0 }' "$metrics_file"
}

mean_metric() {
  local prefix="$1"
  local sum count
  sum="$(metric_value "^${prefix}_sum")"
  count="$(metric_value "^${prefix}_count")"
  awk -v sum="${sum:-0}" -v count="${count:-0}" 'BEGIN { if (count == 0) print "n/a"; else printf "%.4f", sum / count }'
}

context_success="$(metric_sum '^ai_factory_mcp_context_shadow_runs_total.*outcome="success"')"
context_failure="$(metric_sum '^ai_factory_mcp_context_shadow_runs_total.*outcome="failure"')"
coverage_mean="$(mean_metric 'ai_factory_mcp_context_shadow_file_coverage_ratio')"
citation_mean="$(mean_metric 'ai_factory_mcp_context_shadow_citation_validity_ratio')"
direct_chars="$(metric_value '^ai_factory_mcp_context_shadow_chars_sum.*source="direct"')"
mcp_chars="$(metric_value '^ai_factory_mcp_context_shadow_chars_sum.*source="mcp"')"
sandbox_success="$(metric_sum '^ai_factory_mcp_sandbox_shadow_runs_total.*outcome="success"')"
sandbox_failure="$(metric_sum '^ai_factory_mcp_sandbox_shadow_runs_total.*outcome="(direct_failure|mcp_failure)"')"
sandbox_equal="$(metric_sum '^ai_factory_mcp_sandbox_shadow_comparisons_total.*result="equal"')"
sandbox_different="$(metric_sum '^ai_factory_mcp_sandbox_shadow_comparisons_total.*result="different"')"

mkdir -p "$(dirname "$report_path")"
{
  printf '# Rapport de campagne MCP shadow\n\n'
  printf -- '- Généré à : `%s`\n' "$generated_at"
  printf -- '- Source : `%s/actuator/prometheus`\n' "$orchestrator_url"
  printf -- '- Autorité de décision : chemin direct\n\n'
  printf '## Contexte dépôt\n\n'
  printf '| Mesure | Valeur |\n|---|---:|\n'
  printf '| Runs shadow réussis | %s |\n' "$context_success"
  printf '| Runs shadow en échec | %s |\n' "$context_failure"
  printf '| Couverture moyenne des fichiers | %s |\n' "$coverage_mean"
  printf '| Validité moyenne des citations | %s |\n' "$citation_mean"
  printf '| Caractères directs cumulés | %s |\n' "${direct_chars:-0}"
  printf '| Caractères MCP cumulés | %s |\n\n' "${mcp_chars:-0}"
  printf '## Sandbox\n\n'
  printf '| Mesure | Valeur |\n|---|---:|\n'
  printf '| Comparaisons réussies | %s |\n' "$sandbox_success"
  printf '| Échecs direct/MCP | %s |\n' "$sandbox_failure"
  printf '| Sorties exactement égales | %s |\n' "$sandbox_equal"
  printf '| Sorties différentes | %s |\n\n' "$sandbox_different"
  printf '## Décision de gate\n\n'
  printf -- '- [ ] Au moins 20 tâches représentatives ont terminé en shadow.\n'
  printf -- '- [ ] La couverture des fichiers utiles est supérieure ou égale à 90 %% sur la campagne.\n'
  printf -- '- [ ] Toutes les citations sont vérifiables et liées au bon commit.\n'
  printf -- '- [ ] Le contexte MCP réduit les caractères/tokens sans régression du plan évaluée manuellement.\n'
  printf -- '- [ ] `validate_patch`, tests, qualité et sécurité ont chacun une comparaison sandbox réussie.\n'
  printf -- '- [ ] Les divergences sandbox sont expliquées et acceptées, ou corrigées.\n'
  printf -- '- [ ] Le Product Owner et le représentant RSSI autorisent la phase `MCP_ACTIVE`.\n\n'
  printf '## Extrait des métriques MCP\n\n```text\n'
  awk '/^ai_factory_mcp_(context_shadow|sandbox_shadow)/ { print }' "$metrics_file"
  printf '```\n'
} > "$report_path"

printf 'Rapport MCP shadow généré : %s\n' "$report_path"
