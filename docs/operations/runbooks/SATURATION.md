# Runbook — saturation et backlog

## Objectif

Rétablir le débit sans perdre l'ordre Temporal, dupliquer un effet ou augmenter aveuglément la capacité. Ce
runbook couvre `AiFactoryTaskQueueBacklog`, les files Temporal et la file de jobs du Sandbox MCP.

## Confinement immédiat

1. Ouvrir un incident et relever l'heure, les files, le premier workflow affecté et les changements récents.
2. Suspendre toutes les nouvelles admissions : la bascule Temporal est complète et le pipeline n'est pas une voie
   locale de secours.
3. Ne pas relancer manuellement une activité avec effet. Une issue inconnue est réconciliée par sa clé
   d'idempotence.
4. Ne supprimer ni historique Temporal, ni volume `sandbox-job-state`, ni preuve pour vider une file.

## Diagnostic

```bash
docker compose --env-file .env -f infrastructure/compose.yaml ps
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 orchestrator temporal sandbox-execution-mcp
make temporal-status
```

Dans le dashboard SigNoz « AI Factory Global », comparer :

```promql
max by (perimeter) (ai_task_queue_saturation_ratio)
max(ai_factory_sandbox_jobs_queued)
histogram_quantile(0.95, sum by (le, perimeter) (rate({__name__="ai_task_queue_wait.bucket"}[10m])))
sum by (role) (rate({__name__="ai_agent_duration.count",outcome="error"}[10m]))
```

Identifier le goulot avant toute action : workers non pollants, dépendance MCP indisponible, quotas sandbox,
latence LLM, base Temporal ou ressource hôte. Distinguer backlog croissant et backlog stable en cours de drainage.

## Rétablissement

1. Restaurer d'abord la dépendance défaillante ou appliquer le
   [runbook worker](WORKER-TEMPORAL-DEFAILLANT.md) à la file qui ne poll plus.
2. Laisser les retries Temporal et le backpressure sandbox préserver l'ordre ; n'autoriser une relance opérateur
   que pour une délégation marquée récupérable.
3. N'augmenter capacité ou quotas qu'après vérification CPU, mémoire, PID, licences et isolation par tâche.
4. Réouvrir les admissions par décision opérateur quand le backlog décroît sur deux fenêtres de dix minutes.

## Vérification et clôture

- saturation sous `0,90` et file sandbox sous `20` pendant vingt minutes ;
- temps d'attente p95 revenu à la baseline ;
- aucun effet dupliqué, job orphelin ou preuve partielle ;
- tâches échantillonnées corrélées de l'intention à la PR ;
- cause, mesures avant/après et décision de réouverture consignées dans l'incident.

## Escalade

Escalader à Exploitation après dix minutes sans drainage, à l'équipe Temporal si aucune file n'est pollée, et à
Sécurité si la saturation résulte d'un abus, d'une fuite inter-tâches ou d'un contournement de quota. Déclencher
le [rollback](ROLLBACK-MULTI-AGENTS.md) si les seuils de la politique de rollback sont atteints.

## Corrélation OpenTelemetry

Utiliser les dashboards Supervisor, Sandbox et Collector dans SigNoz pour distinguer backlog métier, saturation
de runner et file d'export. Une file Collector pleine ne justifie jamais l'augmentation d'un quota métier.
