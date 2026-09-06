# A2A-025 — Baseline opérationnelle avant coupure

## Résultat

La campagne pré-A2A `MCP-agent-ab-20260902-01` est figée comme référence opérationnelle de bout en bout. Les
distributions utilisent la méthode nearest-rank sur 20 tâches exécutées en série.

| Mesure | Valeur |
|---|---:|
| Latence p50 | 18 363 ms |
| Latence p95 | 66 272 ms |
| Latence p99 | 80 544 ms |
| Tokens p50 | 6 651 |
| Tokens p95 | 12 634 |
| Tokens p99 | 13 306 |
| Échecs terminaux | 19/20, soit 95 % |
| Concurrence de campagne | 1 tâche maximum |
| Payload de soumission reconstruit p50/p95/p99 | 228/237/237 octets |

Le taux de 95 % décrit les statuts fonctionnels de cette campagne difficile ; il ne constitue pas un taux d'échec
du transport. Le coût fournisseur, la mémoire, les tailles de réponses/messages internes et la ventilation par
rôle ne furent pas collectés. Le manifeste les encode avec les statuts `UNAVAILABLE_NOT_ZERO`, `UNAVAILABLE` ou
`UNAVAILABLE_MONOLITHIC_AGGREGATE` afin qu'une valeur absente ne soit jamais comparée à zéro.

Cette absence par rôle est elle-même une caractéristique de la baseline : les rôles partageaient une JVM et
l'artefact de campagne n'enregistrait que les agrégats de la tâche. Après extraction, la comparaison devra ajouter
les dimensions bornées rôle, skill, opération et résultat, sans IDs de tâche/workflow/message dans les métriques.

## Reproduction

Le manifeste machine-readable est
`resources/a2a/baselines/pre-cutover-operational-v1.json`. Il scelle par SHA-256 les résultats JSONL, les cas et le
runner qui prouve l'exécution séquentielle. La commande suivante recalcule les distributions, la taille des
requêtes compactes, les statuts et les marqueurs d'indisponibilité :

```shell
ruby scripts/verify-a2a-operational-baseline.rb
```

La reconstruction du payload représente uniquement la requête HTTP de soumission créée par le runner avec le
propriétaire Gitea local par défaut `aiadmin`. Elle est marquée `RECONSTRUCTED_NOT_OBSERVED` et ne doit pas être
présentée comme une mesure de payload A2A.
