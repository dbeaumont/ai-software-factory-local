# OTEL-077 — exercice de rollback atomique

## Scénario exécuté

Après les pannes Collector/backend, la perte simulée et la saturation couvertes par OTEL-070/076, un exercice de
rollback complet a été exécuté le 5 septembre 2026 :

1. création d'un worktree détaché sur `observability-prometheus-grafana-final` (`06955bec…`) ;
2. validation de son Compose avec la configuration locale, sans copie de secret dans Git ;
3. arrêt de la pile OpenTelemetry sans `--volumes` ;
4. construction et démarrage de toutes les applications du tag ;
5. vérification de l'orchestrateur, de Prometheus et de Grafana ;
6. vérification qu'aucun Collector ni SigNoz ne tournait en parallèle ;
7. arrêt de la pile legacy sans supprimer les volumes ;
8. reconstruction et démarrage complets de la branche OpenTelemetry ;
9. vérification de l'absence de conteneur legacy, des healthchecks, de 605 métriques, sept dashboards, 15 alertes,
   57 requêtes et des rétentions `720h/360h/15d` ;
10. suppression du worktree temporaire.

## Résultats

- Prometheus legacy : `Prometheus Server is Ready` ;
- Grafana legacy : base `ok`, version `12.1.1` ;
- orchestrateur legacy : readiness `UP` ;
- chaîne courante : Collector et SigNoz sains, six applications saines, aucun conteneur Prometheus/Grafana ;
- données SigNoz conservées à travers l'exercice ;
- les quatre flags de capture prompts/résultats/preuves/contenu GenAI sont `false` par défaut dans le tag legacy.

Verdict : **PASS**. Le rollback et le retour avant sont atomiques, sans mélange de chaînes ni suppression de volume.

## Limite

Le canal historique n'a pas été relié à un destinataire externe pendant l'exercice afin d'éviter une notification
réelle ou doublonnée. Sa procédure de restauration et de déduplication reste documentée dans le runbook.
