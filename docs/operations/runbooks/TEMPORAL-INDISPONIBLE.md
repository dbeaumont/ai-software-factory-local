# Runbook — Temporal indisponible

## Objectif

Restaurer le coordinateur durable sans perdre l'historique, redémarrer aveuglément des effets ou créer une seconde
exécution pour le même workflow.

## Confinement immédiat

1. Suspendre les nouvelles admissions hiérarchiques et ouvrir un incident avec l'heure de la dernière progression.
2. Ne supprimer ni `temporal-db-data`, ni namespace, ni historique ; ne pas terminer les workflows en masse.
3. Geler les effets externes dont le résultat est inconnu et conserver leurs clés d'idempotence.
4. Laisser le pipeline traiter uniquement les nouvelles tâches explicitement routées vers lui.

## Diagnostic

```bash
docker compose -f infrastructure/compose.yaml ps temporal temporal-db orchestrator
docker compose -f infrastructure/compose.yaml logs --tail=200 temporal temporal-db orchestrator
```

Vérifier séparément : santé PostgreSQL, port `7233`, espace disque, mémoire, métriques Temporal, puis présence des
pollers sur les sept task queues configurées. L'UI locale d'investigation est exposée par défaut sur
`http://127.0.0.1:8233`; elle ne doit pas servir à supprimer l'historique.

## Rétablissement

1. Restaurer PostgreSQL avant Temporal, puis confirmer la santé du frontend Temporal.
2. Redémarrer l'orchestrateur seulement après disponibilité du namespace et des task queues.
3. Laisser les workflows reprendre depuis leur historique. Réconcilier chaque activité à effet dont l'issue est
   inconnue avant d'autoriser un retry.
4. Si la version worker a changé, appliquer la politique
   [Worker Versioning](../../qualification/multi-agents/policies-and-operations/POLITIQUE-VERSIONNEMENT-WORKFLOWS-TEMPORAL.md) ; ne pas forcer un workflow
   historique sur un code incompatible.

## Vérification et clôture

- Temporal, base et orchestrateur sains ;
- toutes les task queues attendues pollées et leur attente en décroissance ;
- chronologie d'un échantillon de workflows intacte après reprise ;
- aucun doublon SCM, sandbox ou evidence ;
- nouvelles admissions réouvertes progressivement après vingt minutes stables.

## Escalade

Escalader immédiatement à Exploitation pour corruption ou indisponibilité de la base, à l'équipe Temporal pour
historique non relisible ou erreur de compatibilité worker, et à Sécurité si une altération est suspectée. Si la
reprise durable n'est pas démontrée, appliquer le [rollback](ROLLBACK-MULTI-AGENTS.md).
