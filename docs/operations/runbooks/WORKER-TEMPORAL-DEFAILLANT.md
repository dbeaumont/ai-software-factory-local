# Runbook — worker Temporal défaillant

## Détection et confinement

Déclencher cette procédure sur absence de poller, échecs répétés de workflow task, erreur non déterministe ou
build ID incompatible. Suspendre les admissions de la file affectée, relever namespace, task queue, workflow type,
build ID et première exécution touchée. Ne jamais enregistrer un second worker non qualifié sur la même file.

```bash
make temporal-status
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=300 orchestrator temporal
```

Dans SigNoz, vérifier `ai.temporal.task.queue.pollers`, backlog, schedule-to-start, retries et timeouts. Dans Temporal
UI, distinguer une activité en retry d'une workflow task non déterministe. Ne pas terminer l'exécution pour dégager
la file.

## Rétablissement

1. Si le processus seul est défaillant, redémarrer l'orchestrateur au même commit/build ID et vérifier les pollers.
2. Si le code est incompatible, appliquer [ROLLBACK-TEMPORAL.md](ROLLBACK-TEMPORAL.md) vers le dernier build dont le
   replay est qualifié ; ne jamais renommer une queue pour masquer l'incompatibilité.
3. Si une activité à effet a perdu son accusé, appliquer [EFFET-ISSUE-INCONNUE.md](EFFET-ISSUE-INCONNUE.md) avant tout
   retry.
4. Réouvrir uniquement après drainage durable et réussite d'un workflow sans effet sur chaque file rétablie.

La clôture exige pollers présents sur les sept files, backlog décroissant, aucun non-déterminisme, aucun effet
dupliqué et cause racine reliée au build fautif.
