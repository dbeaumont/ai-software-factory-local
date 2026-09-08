# TEMP-108 — Vérification avant réouverture

> Résultat : `PASS`
>
> Date : `2026-09-06`
>
> Admissions : fermées (`temporal_cutover`, révision `2`)

## Temporal et application

| Contrôle | Résultat |
|---|---|
| Namespace `ai-factory-local` | présent |
| Search Attributes | `AiFactoryTaskId`, `AiFactoryAttemptId`, `AiFactoryRepositoryId`, `AiFactoryExecutionMode` présents |
| Worker Deployment | `ai-factory-orchestrator:0.1.0` courant |
| Task queues | `7/7` avec poller : workflow, context, LLM, sandbox, assurance, Evidence et SCM |
| Readiness orchestrateur | `UP` |
| Temporal UI | HTTP `200` |
| Workflows ouverts | `0` |
| Image orchestrateur active | `sha256:59717e13f82355352f73a8eca8e9c17219f9c8079b94c3ec693f398f08b7d374` |

## Projection et dépendances

| Contrôle | Résultat |
|---|---|
| Schéma PostgreSQL | `public`, `27` tables |
| Tâches projetées et accessibles par API | `120` |
| Snapshots de projection | `120` |
| Événements de projection idempotents | `1 129` |
| Evidence MCP readiness | `UP` |
| Repository Context MCP | disponible |
| Sandbox Execution MCP | disponible |

## Observabilité

La vérification authentifiée de SigNoz confirme :

- `784` métriques disponibles ;
- `7` dashboards gérés avec variables et liens opérationnels ;
- `15` règles d'alerte gérées ;
- rétention bornée à `720 h` pour les métriques, `360 h` pour les traces et `15 jours` pour les logs ;
- télémétrie applicative, workflow, activités, MCP et sandbox présente.

Tous les contrôles ont été exécutés sans nouvelle admission. Le verrou reste fermé jusqu'au smoke test TEMP-109.
