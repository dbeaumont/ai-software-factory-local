# Preuve OTEL-100 — bascule runtime atomique

- Date : 2026-09-05
- Environnement : Docker Desktop macOS Apple Silicon
- Décision : Collector OpenTelemetry et SigNoz remplacent immédiatement Prometheus et Grafana.

## Fenêtre de bascule et gel

La fenêtre OTEL-100 est bornée à **30 minutes** à partir de l'arrêt de la pile historique. Les modifications
concurrentes d'instrumentation, de dashboards, d'alertes, de rétention et de routage sont gelées à `T-30 min` et
jusqu'à la décision enregistrée à `T+30 min`. Seuls les commits OTEL-100, la configuration locale préparée et le
rollback atomique documenté sont autorisés pendant cette période.

Jalons obligatoires : sauvegarde et contrôles préalables à `T-15`, arrêt legacy à `T0`, nouvelle pile saine avant
`T+10`, ingestion des trois signaux avant `T+15`, dashboards et alertes validés avant `T+20`, décision au plus tard
à `T+30`. Une pile non saine à `T+10`, un signal absent à `T+15`, une règle critique manquante à `T+20` ou toute
coexistence legacy/OTel déclenche immédiatement le rollback complet. Le gel n'est levé qu'après le smoke test final
et l'enregistrement du commit, des résultats et de la décision.

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
| Parenté HTTP → tâche asynchrone → LLM/MCP | observation capturée avant mise en file et rouverte dans le worker |

## Continuité du contexte asynchrone

L'admission `POST /api/tasks` reste sur le thread instrumenté de la requête jusqu'à la création de l'observation
`ai.factory.task`. Cette observation capture le parent HTTP avant l'appel à l'`ExecutorService`, est rouverte dans
le worker pendant toute l'exécution automatique, puis arrêtée avec l'état final comme résultat borné. Les spans
pipeline, LLM et MCP créés par `ExecutionTracer` héritent donc du même parent ; l'injection W3C des appels MCP part
du span actif au lieu de fabriquer une nouvelle racine. `AsyncTaskTracerTest` vérifie explicitement la parenté
HTTP → tâche asynchrone → enfant LLM à travers le changement de thread.

Le même test lance aussi deux observations de tâche sur les deux workers disponibles, bloque leur travail sur une
barrière commune et vérifie que les deux spans sont démarrés avant que l'un ou l'autre soit arrêté. Ils portent le
même parent HTTP tout en restant deux enfants distincts : le fan-out parallèle est donc représenté par des spans
frères réellement superposés, et non par une chaîne séquentielle artificielle.

La reprise après approbation conserve le nouvel appel HTTP comme parent effectif, mais ajoute un span
`ai.factory.task.continuation` lié au contexte de l'exécution initiale avec `ai.link.type=continuation`. Cette
relation est un `Span Link` parce que l'attente humaine sépare deux exécutions qui ne forment pas une parenté
temporelle stricte. Le contexte mémorisé est supprimé dès la reprise afin de borner la mémoire. Le test
`AsyncTaskTracerTest.linksAnApprovalResumeToThePreviousExecutionContext` vérifie le lien et sa fermeture.

La suite `SandboxJobServiceTest` vérifie en outre les cinq issues de span contractuelles : `succeeded` sans erreur,
`rejected` métier sans erreur technique, `failed` avec exception, `timed_out` avec exception de délai et `cancelled`
sans transformer la demande opérateur en panne. Chaque observation couvre la soumission, l'attente en file et le
résultat asynchrone complet.

`ResilientMcpToolInvokerTest` complète cette matrice au niveau MCP : un premier essai retryable suivi d'un succès
arrête le span sans erreur, un dépassement du délai l'arrête avec `McpInvocationException/TIMEOUT`, et une réponse
de contrat invalide l'arrête avec l'exception terminale sans retry. La combinaison avec les cas sandbox couvre donc
retry, timeout, annulation, rejet métier et exception technique.

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
