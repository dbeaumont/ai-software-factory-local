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

## Alertes

Les neuf règles conservent nom, expression normalisée, seuil, fenêtre, severity, composant, résumé, description et
runbook. `test-signoz-alert-fixtures.sh` injecte huit instruments et les séries étiquetées nécessaires sous forme
OTLP déterministe; les neuf expressions retournent ensuite un résultat positif.

Une divergence est acceptée et testée : `AiFactoryEvidenceAltered` avait une durée historique nulle et utilise
désormais une fenêtre d'évaluation de 1 minute, cohérente avec la fréquence SigNoz et moins sensible aux transitoires.

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
