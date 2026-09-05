# Preuve OTEL-100 — bascule runtime atomique

- Date : 2026-09-05
- Environnement : Docker Desktop macOS Apple Silicon
- Décision : Collector OpenTelemetry et SigNoz remplacent immédiatement Prometheus et Grafana.

## Contrôles exécutés

| Contrôle | Résultat |
|---|---|
| Tests Maven des six applications Spring | succès |
| `docker compose --env-file .env -f infrastructure/compose.yaml config --quiet` | succès |
| Validation Collector 0.160.0 avec le digest versionné | succès |
| Collecte OTLP métriques, traces et logs | succès |
| Ressources applicatives observées | orchestrateur et cinq MCP |
| Métriques disponibles dans SigNoz | 587 |
| Dashboards provisionnés | 7 |
| Alertes provisionnées et routées | 9 |
| Validation des requêtes SigNoz | 51 requêtes uniques valides |
| Scanner de redaction par canary OTLP | succès |
| Receiver Temporal sans serveur Prometheus | succès |
| Recherche de service dans les traces | 6 services, 2 265 spans avant test de redémarrage |
| Logs OTLP persistés | 114 avant test de redémarrage |

## Résilience observée

- L'arrêt complet du Collector n'a interrompu aucune des six applications ; l'ingestion a repris après son
  redémarrage.
- L'arrêt de l'ingester SigNoz a laissé les six applications et le Collector sains. Le Collector a borné les
  retries dans sa file et l'ingestion est repartie au redémarrage du backend.
- Un document invalide envoyé à `/v1/traces` a reçu HTTP 400. Le Collector est resté sain et n'a pas journalisé
  le corps rejeté.
- Après redémarrage de SigNoz, PostgreSQL, ClickHouse et de l'ingester, les dashboards, alertes et rétentions sont
  restés disponibles. Les compteurs ClickHouse sont passés de 2 265 à 2 300 spans et sont restés à 114 logs :
  les données antérieures n'ont pas été perdues.

## Sécurité et retrait legacy

- Les ports OTLP, ClickHouse et PostgreSQL SigNoz ne sont pas publiés sur l'hôte.
- Le Collector est en lecture seule, non-root, sans capability et avec `no-new-privileges`.
- Aucune socket Docker n'est montée dans Collector ou SigNoz.
- Les services, ports, volumes Compose, endpoints Actuator, dépendances Maven et fichiers actifs Prometheus/Grafana
  ont été retirés. Le seul terme runtime restant est le receiver de compatibilité `prometheus/temporal`, sans
  serveur Prometheus.
- Les volumes Grafana détachés sont sauvegardés hors dépôt et ne seront détruits qu'après leur date d'expiration
  et une validation explicite.

## Limites de cette preuve

- Les scénarios nécessitant un modèle LLM cloud actif et un cluster GKE de validation sont suivis séparément ;
  ils ne sont pas simulés avec de faux credentials.
- La destruction différée des volumes Grafana n'appartient pas à OTEL-100 et reste récupérable.
