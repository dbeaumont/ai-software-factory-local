# Preuve OTEL-101 — qualification macOS

- Date : 2026-09-05
- Commit de bascule : `a7501e0`
- Plateforme : Docker Desktop sur macOS Apple Silicon (`linux/arm64`)
- Résultat : qualifié pour le développement local.

## Installation et architecture

- Le rendu Compose est valide avec `.env` initialisé et la pile démarre avec SigNoz comme seule interface
  d'observabilité publiée sur le port 3301.
- Les manifestes multi-architecture du Collector 0.160.0 et de SigNoz 0.135.0 contiennent `linux/amd64` et
  `linux/arm64`; les digests de listes de manifests sont ceux versionnés dans Compose.
- Aucun conteneur dont le nom contient `prometheus` ou `grafana` n'est actif.
- Les six applications, le Collector, l'ingester, SigNoz, PostgreSQL et ClickHouse sont sains.

## Signaux et parcours opérateur

- 587 métriques sont requêtables, dont les métriques métier historiques, HTTP, JVM, MCP, sandbox et Temporal.
- Les six services Spring apparaissent dans les traces. Le workflow de qualification en erreur `bf10043e` a
  produit un POST API, des appels MCP corrélés entre orchestrateur et Repository Context, puis des appels LLM.
- Les logs du workflow sont exportés sans prompt ni réponse. Un log d'accès actif porte le
  `trace_id=9191ebd9aeb48cf1de27b3d0f27f2e7b` et le `span_id=e5e50d9aa11edc4d`, démontrant la navigation
  log → trace lorsqu'un contexte est actif.
- Sept dashboards, neuf alertes métier et six alertes techniques sont provisionnés de façon idempotente; leurs
  57 requêtes uniques sont valides.

## Persistance et résilience

- L'arrêt puis la reprise du Collector et de l'ingester ne rendent pas les applications indisponibles.
- Le redémarrage de PostgreSQL, ClickHouse, ingester et SigNoz conserve les données, dashboards et alertes.
- Les compteurs avant/après redémarrage du stockage sont respectivement `2265/114` puis `2300/114` pour
  spans/logs : aucune régression de persistance n'est observée.
- Un payload OTLP invalide reçoit HTTP 400, sans corps réaffiché dans les logs du Collector.

## Limite externe

Le scénario nominal cloud documenté n'a pas été relancé après la fixture d'erreur : l'autorisation d'exécution a
été refusée car elle aurait envoyé le ticket et du contexte de dépôt à un fournisseur externe. La qualification
du transport local utilise donc le workflow d'erreur réel et les suites d'intégration déterministes. Aucun faux
credential et aucun contournement n'ont été employés.
