# OpenTelemetry — état de livraison

La migration immédiate est réalisée : toutes les applications actives exportent métriques, traces et logs en OTLP
vers un OpenTelemetry Collector. SigNoz remplace intégralement l'ancienne interface locale et Google Cloud
Observability est la cible des manifests GKE.

## Livré

- instrumentation Micrometer/OpenTelemetry des six applications Spring Boot ;
- propagation W3C HTTP et MCP, corrélation `trace_id`/`span_id` dans les logs ;
- Collector durci, pipelines séparés et redaction des attributs sensibles ;
- SigNoz local persistant, sept dashboards, neuf alertes métier et six alertes techniques ;
- provisioning idempotent, validation des requêtes, fixtures d'alertes et tests de résilience ;
- gateway GKE avec mTLS, Workload Identity, NetworkPolicy, HPA et PDB ;
- retrait du runtime, des dépendances et des liens opérateur historiques.

## Vérification locale

```bash
make up
./scripts/check-signoz-telemetry.sh
./scripts/validate-signoz-queries.sh
./scripts/test-otel-redaction.sh
./scripts/test-otel-resilience.sh
```

## Restant avant qualification de production

- campagne de charge et de saturation avec mesure des SLO ;
- sauvegarde/restauration du backend local ;
- campagne nominale et dégradée sur un cluster GKE de validation ;
- exercice humain de notification et d'incident ;
- approbations plateforme, sécurité, exploitation et produit.

Voir le [plan de migration détaillé](../migrations/remplacement-prometheus-grafana-opentelemetry.md), la
[stratégie courante](strategie-opentelemetry.md) et les [preuves](../../evidence/observability/README.md).
