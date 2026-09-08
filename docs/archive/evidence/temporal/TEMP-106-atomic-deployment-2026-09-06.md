# TEMP-106 — Déploiement atomique

> Résultat : `PASS`
>
> Date : `2026-09-06`
>
> Source applicative approuvée : `a589f5c4c93088650bc7f46a13655cf10b92bf94`

## Préconditions

| Contrôle | Résultat |
|---|---|
| Gate final TEMP-103R | `APPROVED` par David Beaumont |
| Gel `temporal-cutover-v1` | `PASS` |
| Admissions | fermées, motif `temporal_cutover`, révision `2` |
| Workflows Temporal ouverts | `0` |
| Image orchestrateur disponible | image exacte approuvée |
| Image interface disponible | image exacte approuvée |

## Déploiement

Docker Compose a convergé l'ensemble de la stack avec `--no-build --remove-orphans`. Cette option interdit toute
reconstruction après le gate. Les volumes existants ont été conservés et l'orchestrateur a été recréé avec l'image
exacte approuvée.

| Artefact actif | Identité |
|---|---|
| Orchestrateur | `sha256:59717e13f82355352f73a8eca8e9c17219f9c8079b94c3ec693f398f08b7d374` |
| Interface | `sha256:59beb07a9c24abb3882465d4bc5f5977b398418714ab29ad74fd67c8f2a8521c` |
| Worker Deployment | `ai-factory-orchestrator:0.1.0`, version courante |
| Migration projection | `015|durable admission control|true` |

## Contrôles de convergence

- les `31` services persistants attendus sont en état `running` ;
- tous les services qui définissent un healthcheck sont `healthy` ;
- `temporal-worker-activation` est terminé avec le code `0` ;
- `signoz-bootstrap` est terminé avec le code `0` ;
- les dashboards AI Factory Global, Agents, MCP, Sandbox, Supervisor, Temporal et OpenTelemetry Collector sont mis à
  jour ;
- les `15` règles d'alerte prévues sont mises à jour et le canal `ai-factory-local` est prêt ;
- l'orchestrateur est sain depuis `2026-09-06T07:18:57Z` ;
- les admissions sont toujours fermées à la révision `2` après redémarrage.

TEMP-106 ne rouvre aucun trafic. La vérification détaillée TEMP-108 et le smoke test TEMP-109 restent obligatoires
avant TEMP-110.
