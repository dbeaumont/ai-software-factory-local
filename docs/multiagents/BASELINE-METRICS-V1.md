# Mesures de référence du pipeline V1

La campagne appariée `MCP-agent-ab-20260902-01`, exécutée sur 20 cas le 2 septembre 2026, constitue le rejeu de
référence du pipeline figé `v1.1.0-mcp`. Les résultats source sont conservés dans
[`MCP-agent-ab-20260902-01-baseline.jsonl`](../mcp/baselines/MCP-agent-ab-20260902-01-baseline.jsonl) et leur
agrégat exploitable par les futures comparaisons dans
[`pipeline-v1-metrics.json`](../../resources/multiagents/baselines/pipeline-v1-metrics.json).

| Mesure | Baseline |
|---|---:|
| Cas | 20 |
| Premier patch réussi | 75 % |
| Réparations moyennes | 0,35 |
| Tests réussis | 10 % |
| Acceptation Reviewer | 5 % |
| Acceptation humaine | 0 % |
| Tokens moyens | 6 718,25 |
| Durée moyenne | 27 152,05 ms |
| Échecs sécurité | 0 |

La campagne a produit 19 statuts `FAILED` et un statut `WAITING_APPROVAL`. Trois incidents de protocole ou de
mesure ont été consignés : incompatibilité des noms d'outils avec le transport, contradiction des prompts sur
l'accès aux outils et faux positifs de classification sécurité. Le détail des corrections et le verdict sont dans
[`MCP-180-rapport-campagne-20260902.md`](../mcp/MCP-180-rapport-campagne-20260902.md).

La valeur de coût observée, zéro, n'est pas une mesure de gratuité : la télémétrie fournisseur était absente.
L'agrégat l'encode explicitement par `telemetry_available: false` et `UNAVAILABLE_NOT_ZERO` afin qu'aucune future
comparaison ne traite cette valeur comme un coût réel.
