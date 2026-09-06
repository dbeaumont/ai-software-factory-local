# Runbook — agent A2A indisponible

## Détection

Les alertes `AiFactoryA2aPollerAbsent`, `AiFactoryA2aAgentNotReady` et `AiFactoryA2aFailureRate` couvrent poller
absent, readiness en échec et taux de terminaison `FAILED`/`REJECTED` supérieur à 5 %.

## Confinement immédiat

Fermer les nouvelles admissions du seul rôle affecté et laisser Temporal conserver les workflows en attente.
Ne pas router vers un autre rôle aux permissions différentes et ne pas restaurer l'ancien appel agent in-process.

## Diagnostic

```bash
make a2a-status
docker compose --env-file .env -f infrastructure/compose.yaml ps
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full logs --tail=200 a2a-developer
```

Dans SigNoz, comparer readiness, pollers, backlog, erreurs A2A et dépendances PostgreSQL, Temporal, MCP, LLM,
OAuth2 et mTLS. Le détail de readiness désigne la dépendance fautive ; les IDs restent dans traces/logs.

## Rétablissement

Réparer la dépendance ou redéployer exactement l'image qualifiée du rôle. Reprendre d'abord les tâches durables
existantes ; une issue inconnue se réconcilie par `messageId`, sans nouveau dispatch.

## Vérification et clôture

Exiger readiness verte, au moins un poller, backlog décroissant, zéro divergence et deux fenêtres d'alerte saines.
Confirmer dans Temporal UI que les workflows existants ont repris sans nouvelle exécution métier.

## Escalade

Escalader à `agent-platform`, puis à Temporal/MCP/Sécurité selon la dépendance. Suivre
[Rollback A2A](ROLLBACK-A2A.md) en cas de régression du runtime.
