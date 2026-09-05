# Runbook — Temporal indisponible

## Objectif

Restaurer le coordinateur durable sans perdre l'historique, redémarrer aveuglément des effets ou créer une seconde
exécution pour le même workflow.

## Confinement immédiat

1. Suspendre toutes les nouvelles admissions et ouvrir un incident avec l'heure de la dernière progression.
2. Ne supprimer ni `temporal-db-data`, ni namespace, ni historique ; ne pas terminer les workflows en masse.
3. Geler les effets externes dont le résultat est inconnu et conserver leurs clés d'idempotence.
4. Ne pas réactiver l'ancien coordinateur local : après la bascule franche, le pipeline est lui aussi un workflow
   Temporal et aucune voie de contournement n'est supportée.

## Diagnostic

```bash
docker compose --env-file .env -f infrastructure/compose.yaml ps temporal temporal-db orchestrator
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 temporal temporal-db orchestrator
make temporal-status
```

Vérifier séparément : santé PostgreSQL, port `7233`, espace disque, mémoire, métriques Temporal, puis présence des
pollers sur les sept task queues configurées. L'UI locale d'investigation est exposée par défaut sur
`http://127.0.0.1:8233`; elle ne doit pas servir à supprimer l'historique.

## Rétablissement

1. En cas de perte d'état, appliquer la procédure [TEMP-086](../../qualification/temporal/TEMP-086-BACKUP-RESTORE.md)
   dans l'ordre Evidence, bases Temporal, puis projection. Sinon, ne restaurer aucun snapshot inutilement.
2. Redémarrer l'orchestrateur seulement après disponibilité du frontend, du namespace et des Search Attributes.
3. Laisser les workflows reprendre depuis leur historique. Réconcilier chaque activité à effet dont l'issue est
   inconnue avant d'autoriser un retry.
4. Si la version worker a changé, appliquer la politique
   [Worker Versioning](../../qualification/multi-agents/policies-and-operations/POLITIQUE-VERSIONNEMENT-WORKFLOWS-TEMPORAL.md) ; ne pas forcer un workflow
   historique sur un code incompatible. Suivre le [rollback Temporal](ROLLBACK-TEMPORAL.md) si le build worker est en
   cause.

## Vérification et clôture

- Temporal, base et orchestrateur sains ;
- toutes les task queues attendues pollées et leur attente en décroissance ;
- chronologie d'un échantillon de workflows intacte après reprise ;
- aucun doublon SCM, sandbox ou evidence ;
- nouvelles admissions réouvertes seulement après vingt minutes stables et validation opérateur.

## Escalade

Escalader immédiatement à Exploitation pour corruption ou indisponibilité de la base, à l'équipe Temporal pour
historique non relisible ou erreur de compatibilité worker, et à Sécurité si une altération est suspectée. Si la
reprise durable n'est pas démontrée, appliquer le [rollback](ROLLBACK-MULTI-AGENTS.md).

## Corrélation OpenTelemetry

Comparer Temporal UI et SigNoz avec `temporal.workflow.type`, `temporal.activity.type`, namespace et task queue.
Un replay ne doit pas créer de faux effets ; conserver l'historique durable comme autorité temporelle.
