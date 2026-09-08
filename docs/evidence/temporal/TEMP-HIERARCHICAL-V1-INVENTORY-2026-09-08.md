# Inventaire Temporal préalable au mode hiérarchique définitif — 2026-09-08

## Périmètre

- Namespace : `ai-factory-local`
- Worker Deployment : `ai-factory-orchestrator`
- Fenêtre d'inventaire : 2026-09-08 de 17:50:12 UTC à 17:52:33 UTC
- Admissions : fermées à la révision 2, puis rouvertes à la révision 3

Les commandes ont été exécutées avec `docker compose --env-file .env -f infrastructure/compose.yaml`. Aucun
secret ni payload de workflow n'est reproduit dans cette preuve.

## Résultats

La requête Temporal `ExecutionStatus="Running"` ne retourne aucun workflow ouvert. La liste des 100 derniers
workflows est également vide. Il n'existe donc aucun workflow ID, run ID, type, phase, mode ou effet en attente à
exporter pour cette instance.

Les projections PostgreSQL confirment cet état :

| Relation | Nombre de lignes |
|---|---:|
| `tasks` | 0 |
| `workflow_runs` | 0 |
| `workflow_attempts` | 0 |
| `pending_effects` | 0 |
| effets ouverts | 0 |

Le Worker Deployment ne contient qu'une version :

| Build ID | Routage | Drainage |
|---|---|---|
| `a2a-cutover-d4b55c7` | courant, 0 % de ramping | non applicable tant qu'il est courant |

## Conclusion de drainage

Aucun historique V1 ouvert ou retenu n'impose de période de drainage sur cette instance au 2026-09-08. Aucun
échantillon réel ne peut être exporté, puisque le namespace ne contient aucun historique. Le Build ID courant
doit néanmoins rester enregistré jusqu'à l'activation et la vérification du worker V2 ; son retrait relève de la
livraison B et non de ce seul inventaire.

La date minimale liée aux historiques pour retirer V1 est donc le 2026-09-08. Le retrait effectif reste
conditionné à la disponibilité du worker V2, à ses tests de replay et à la vérification qu'aucun nouveau workflow
V1 n'a été admis entre-temps.

## Vérification de replay

`make temporal-replay` a rejoué les huit historiques versionnés de
`WorkflowDeterminismArchitectureTest` : 8 tests réussis, aucun échec, aucune erreur et aucun test ignoré.

## Commandes d'audit

```text
make admissions-close
temporal workflow list --namespace ai-factory-local --query 'ExecutionStatus="Running"' --output json
temporal workflow list --namespace ai-factory-local --limit 100 --output json
temporal worker deployment describe --namespace ai-factory-local --name ai-factory-orchestrator --output json
SELECT count(*) FROM tasks;
SELECT count(*) FROM workflow_runs;
SELECT count(*) FROM workflow_attempts;
SELECT count(*) FROM pending_effects;
make admissions-open
make temporal-replay
```
