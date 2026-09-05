# OTEL-070 — Résilience locale et décision de stabilité

## Périmètre

La campagne du 5 septembre 2026 vérifie la continuité du chemin métier lorsque la chaîne locale
OpenTelemetry est indisponible ou reçoit une charge invalide. Elle couvre Docker Compose sur macOS ; elle ne vaut
pas qualification GKE ni test de saturation.

## Résultats reproductibles

La commande `./scripts/test-otel-resilience.sh` a terminé avec succès et a démontré les comportements suivants :

- arrêt de l'ingester SigNoz : les six applications et le Collector restent sains ;
- redémarrage de l'ingester : l'ingestion et l'API SigNoz redeviennent disponibles ;
- arrêt complet du Collector : les six applications restent saines et l'orchestrateur répond sur son endpoint de
  readiness ;
- redémarrage du Collector : le healthcheck repasse à `healthy` et les trois signaux sont de nouveau consultables ;
- envoi d'un document OTLP invalide : réponse HTTP `400`, sans arrêt du Collector ni réaffichage du payload ;
- le `trap` du scénario restaure systématiquement l'ingester et le Collector.

Les six alertes techniques ajoutées couvrent les erreurs d'export, la saturation de file, l'absence d'ingestion,
les redémarrages, la pression mémoire et les données refusées. Avec les neuf alertes métier de parité, SigNoz
provisionne 15 règles gérées. `./scripts/validate-signoz-queries.sh` valide leurs requêtes avec celles des dashboards,
soit 57 requêtes uniques. `./scripts/check-signoz-telemetry.sh` confirme 602 métriques, sept dashboards, 15 règles
et les rétentions bornées attendues.

L'alerte d'erreur d'export constitue le signal externe de défaillance de l'ingester ou de son stockage ClickHouse :
une alerte exécutée dans le même ClickHouse ne pourrait pas signaler de façon fiable sa propre indisponibilité.

## Tests automatisés

- `MultiAgentAlertRulesTest` verrouille les neuf règles de parité et les six règles techniques ;
- `OpenTelemetryParityTest` verrouille les 15 noms, seuils, durées et runbooks attendus ;
- `test-signoz-alert-fixtures.sh` conserve la campagne déterministe des neuf signaux métier historiques ;
- `test-otel-resilience.sh` automatise les pannes et la récupération sans modifier les volumes.

## Décision

La chaîne est **stable pour le développement local et les pannes fonctionnelles testées**. La décision ne couvre pas
encore la saturation, un backend lent, les notifications humaines, la restauration de sauvegarde ni GKE. Ces points
restent explicitement non cochés dans le plan.
