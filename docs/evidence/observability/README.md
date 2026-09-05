# Baseline d'observabilité avant OpenTelemetry

## Capture du 5 septembre 2026

Les fixtures `prometheus-grafana-baseline-2026-09-05.json` et
`prometheus-metric-metadata-2026-09-05.json` figent l'état exploitable avant la bascule atomique vers
OpenTelemetry Collector et SigNoz.

Elle contient :

- 92 noms de métriques exposés par l'orchestrateur ;
- 348 noms de métriques exposés par Temporal ;
- l'absence constatée de registre Prometheus sur les cinq MCP ;
- l'état des quatre cibles configurées, dont deux en erreur HTTP 404 ;
- les six dashboards, leurs titres et leurs expressions ;
- les neuf alertes, requêtes, durées, labels et annotations ;
- les principales statistiques de cardinalité des 9 841 séries actives ;
- les types, unités et descriptions de 258 familles ainsi que les labels et cardinalités observés des 92 séries
  de l'orchestrateur ;
- les 43 fichiers actifs référençant Prometheus, Grafana ou `/actuator/prometheus` ;
- l'empreinte instantanée des conteneurs Prometheus et Grafana ;
- le scénario dégradé `b7da9afc`, terminé sur un rejet de politique de sécurité attendu.

Les quantiles agent et MCP n'étaient pas calculables sur la fenêtre d'une heure : aucune série histogram bucket
correspondante n'était publiée. Cette absence fait partie de la baseline et doit être corrigée dans le contrat
OTLP, pas interprétée comme une valeur nulle.

## Utilisation

- comparer les noms, types, unités et dimensions au contrat OTLP ;
- reconstruire chaque panneau SigNoz depuis l'expression historique correspondante ;
- déclencher chaque alerte avec une fixture déterministe ;
- vérifier que les cibles MCP absentes deviennent observables ;
- comparer cardinalité, ressources et délai d'ingestion après la bascule.

Cette fixture ne contient ni secret, ni prompt, ni résultat LLM, ni code source, ni preuve métier brute.

## Preuves après bascule

- `otel-100-runtime-cutover-2026-09-05.md` consigne les contrôles de la bascule atomique.
- `otel-101-macos-qualification-2026-09-05.md` consigne la qualification Docker Desktop Apple Silicon.
- `otel-050-gke-manifest-validation-2026-09-05.md` consigne la validation hors cluster de la gateway GKE.
- `otel-060-parity-report-2026-09-05.md` compare métriques, dashboards et alertes aux fixtures historiques.
- `otel-070-resilience-stability-2026-09-05.md` consigne les pannes Collector/backend, la récupération et la décision de stabilité locale.
- `otel-075-backup-restore-2026-09-05.md` consigne la sauvegarde cohérente et la restauration dans des volumes isolés.
- `otel-076-persistent-queue-load-2026-09-05.md` mesure la charge courte et le drainage de la file persistante après panne.
- `grafana-volume-backup-2026-09-05.md` consigne les sauvegardes récupérables et leur expiration.
