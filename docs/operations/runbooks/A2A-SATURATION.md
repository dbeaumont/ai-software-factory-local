# Runbook — saturation A2A

## Détection

`AiFactoryA2aBacklog` signale plus de 20 tâches soumises pendant 10 minutes. Corréler avec tâches actives,
pollers, slots Temporal, délai de prise en charge, quotas tenant et ressources PostgreSQL/LLM/MCP.

## Confinement immédiat

Fermer les nouvelles admissions du rôle saturé, sans abandonner les tâches déjà durables. Ne pas augmenter les
quotas, la concurrence ou les budgets LLM avant d'avoir identifié le goulot et vérifié les limites aval.

## Diagnostic

```bash
make a2a-status
docker compose --env-file .env -f infrastructure/compose.yaml ps
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full logs --tail=200 a2a-developer
```

Comparer backlog par rôle, âge maximal, pollers, slots, latences MCP/LLM et connexions DB. Un backlog croissant
avec pollers nuls relève du runbook agent indisponible ; avec pollers saturés, mesurer le service aval dominant.

## Rétablissement

Réparer la dépendance puis rouvrir à capacité fixe. Une hausse de concurrence exige un test de charge et doit
respecter `max workflows`, `max activities`, quotas tenant, pools DB et rate limits A2A.

## Vérification et clôture

Exiger backlog décroissant jusqu'à zéro, p95 de prise en charge conforme, aucun doublon/divergence et deux
fenêtres stables après réouverture. Documenter débit soutenable et nouvelle limite si elle a changé.

## Escalade

Escalader à `agent-platform` et au propriétaire de la dépendance saturée. Déclencher
[Rollback A2A](ROLLBACK-A2A.md) si la saturation suit un changement runtime.
