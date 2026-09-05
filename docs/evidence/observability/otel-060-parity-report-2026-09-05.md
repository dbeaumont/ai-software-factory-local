# Rapport OTEL-060 — parité Prometheus/Grafana vers SigNoz

- Date : 2026-09-05
- Sources : fixtures historiques datées et nouvelle chaîne uniquement
- Résultat technique : parité structurelle et sémantique validée

## Métriques

- 587 métriques sont visibles dans SigNoz après la migration.
- Les instruments métier historiques exigés par dashboards et alertes sont présents avec leurs variantes OTLP
  (`count`, `sum`, `bucket`, `max`) lorsque le type est un histogramme.
- Le suffixe Prometheus `_total` est volontairement absent des compteurs OTLP. C'est la seule réécriture des neuf
  expressions d'alerte; le test `OpenTelemetryParityTest` vérifie cette équivalence exacte.
- Les identifiants non bornés restent exclus des dimensions métriques et réservés aux traces/logs.

## Dashboards

Les six fichiers historiques correspondent chacun à une définition SigNoz v6 : global, Supervisor, agents, MCP,
sandbox et Temporal. Les panneaux peuvent consolider plusieurs expressions historiques, mais le nombre de requêtes
fonctionnelles reste supérieur ou égal à la référence. Un septième dashboard couvre le Collector. Les 51 requêtes
uniques de dashboards et alertes passent l'API SigNoz v5.

L'inspection des six JSON du commit de référence confirme qu'ils ne définissaient aucune variable, unité, valeur de
seuil, légende personnalisée, lien, fenêtre ni fréquence de rafraîchissement. La cible conserve donc les unités
neutres et l'absence de seuil de panneau, fixe explicitement une fenêtre de 6 h et un rafraîchissement de 30 s, puis
ajoute six champs de recherche (`task_id`, rôle, opération, résultat, modèle et service MCP) et cinq liens de pivot
vers traces, logs, Temporal UI, l'API de détail de tâche et les runbooks. Le contrôle
`check-signoz-dashboard-parity.py` vérifie ces invariants et la conservation de chaque expression normalisée.

## Alertes

Les neuf règles conservent nom, expression normalisée, seuil, fenêtre, severity, composant, résumé, description et
runbook. `test-signoz-alert-fixtures.sh` injecte huit instruments et les séries étiquetées nécessaires sous forme
OTLP déterministe; les neuf expressions retournent ensuite un résultat positif.

Une divergence est acceptée et testée : `AiFactoryEvidenceAltered` avait une durée historique nulle et utilise
désormais une fenêtre d'évaluation de 1 minute, cohérente avec la fréquence SigNoz et moins sensible aux transitoires.

## Parité numérique et cas limites

`./scripts/test-signoz-metric-semantics.sh` injecte une fixture OTLP cumulative bornée et interroge le moteur
PromQL de SigNoz. La campagne du 5 septembre 2026 a obtenu, dans les tolérances déclarées : débit agrégé 100,
erreurs 5, p50 0,1 s, p95 0,778 s et p99 1 s. Les quantiles attendus sont calculés depuis les bornes explicites
`[0.1, 0.5, 1, 2, 5]` et les comptes non cumulatifs `[50, 40, 9, 1, 0, 0]`.

La même fixture impose un changement de `startTimeUnixNano` au milieu d'un compteur cumulatif : `resets()` retourne
1. `absent_over_time()` retourne également 1 pour une série volontairement absente. Les redémarrages réels du
Collector et de `repository-context-mcp` sont couverts par les campagnes OTEL-070/076, avec livraison exacte après
reprise ; les périodes sans données ne sont donc ni converties en zéro ni masquées par un doublon.

## Ressources ponctuelles macOS

| Composant | CPU | Mémoire |
|---|---:|---:|
| Collector | 0,42 % | 119,8 MiB |
| SigNoz | 0,49 % | 60,07 MiB |
| Ingester | 0,36 % | 218 MiB |
| ClickHouse | 22,61 % | 2,039 GiB |
| PostgreSQL SigNoz | 0,00 % | 21,37 MiB |

Cette mesure instantanée confirme la recommandation minimale de 4 GiB pour l'observabilité locale. Elle ne vaut
pas dimensionnement GKE ni prévision de coût à charge longue.

## Limites restant ouvertes

- La réception effective de notifications déclenchées et leur acquittement opérateur demandent une fenêtre
  d'exercice dédiée; ici le canal webhook est créé, testé et les expressions sont évaluées positivement.
- La campagne GKE réelle dépend d'un projet et d'un cluster de validation.
- Les approbations formelles sécurité, plateforme et exploitation ne sont pas remplacées par ce rapport technique.
