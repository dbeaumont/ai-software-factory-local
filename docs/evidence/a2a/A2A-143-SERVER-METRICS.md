# Preuve A2A-143 — métriques serveur

Date : 2026-09-06

## Surface instrumentée

Le runtime d'agent exporte via Micrometer et la chaîne OpenTelemetry existante :

| Signal | Instrument |
|---|---|
| Admissions acceptées/refusées | `ai.factory.a2a.server.admissions.accepted`, `ai.factory.a2a.server.admissions.rejected` |
| Refus d'authentification | `ai.factory.a2a.server.auth.refusals` |
| Déduplications | `ai.factory.a2a.server.deduplications` |
| Durée de tâche terminale | `ai.factory.a2a.server.task.duration` |
| Transitions d'état | `ai.factory.a2a.server.transitions` |
| Tâches actives | `ai.factory.a2a.server.active.tasks` |
| Backlog soumis | `ai.factory.a2a.server.backlog` |
| Polling `get`/`list` | `ai.factory.a2a.server.polling` |
| Notifications livrées/rejouées/épuisées | `ai.factory.a2a.server.notifications.delivered`, `.retries`, `.failed` |

Les jauges actives et backlog interrogent l'autorité `A2aTaskStore`, en mémoire ou PostgreSQL. Les durées partent
de `submittedAt` et ne sont enregistrées qu'à une transition terminale confirmée par le store. Les notifications
sont comptées au résultat effectif du transport.

## Cardinalité

Tous les instruments réutilisent `A2aMetricDimensions`. Les seules dimensions sont `agent.role`, `agent.skill`,
`rpc.operation`, `a2a.version` et `task.state`. Les identifiants de tâche, message, workflow, contexte, délégation,
appelant et tenant restent exclusivement dans les traces et journaux corrélés.

## Vérification

```bash
mvn -q -s /opt/homebrew/Cellar/maven/3.9.16/libexec/conf/settings.xml \
  -pl apps/a2a-agent-runtime -am \
  -Dtest=A2aServerMetricsTest,A2aSendMessageServiceTest,A2aPushNotificationSenderTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full build a2a-developer
```

Le test dédié vérifie la présence des compteurs, timers et jauges, leur valeur, et l'absence de labels contenant
des identifiants non bornés. La construction Compose exécute la suite sous JDK 25 avant de produire l'image.
