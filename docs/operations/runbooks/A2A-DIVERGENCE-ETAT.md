# Runbook — divergence d'état A2A/Temporal

## Détection

`AiFactoryA2aStateDivergence` couvre une association, continuation ou réponse distante incompatible.
`AiFactoryA2aIdempotencyCollision` couvre un `messageId` réutilisé avec un contenu ou une cible différents.

## Confinement immédiat

Geler admissions et effets externes du rôle. Préserver historiques Temporal, task store A2A, registre
d'association, notifications et Evidence. Ne modifier aucune de ces autorités à la main.

## Diagnostic

```bash
make a2a-status
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 orchestrator
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full logs --tail=200 a2a-developer
```

Comparer `workflowId/runId/delegationId/messageId/taskId/contextId`, digests et sequences via les traces et les
lectures autorisées. Temporal gouverne l'orchestration ; le task store A2A gouverne l'état protocolaire ; Evidence
gouverne les artefacts. Toute discordance est une preuve, pas une invitation à écraser une valeur.

Pour une même séquence, une divergence réelle existe si `agentRole`, `taskId`, `contextId`, `state` ou la liste
complète des artefacts diffère. La comparaison des artefacts inclut leur contenu et leurs metadata. En revanche,
une différence limitée à `occurredAt` ou aux metadata de provenance entre `tasks/get` et callback est une variation
de transport normale ; l'inbox conserve malgré tout sa comparaison stricte du digest entre deux callbacks.

## Rétablissement

Pour une issue inconnue, rechercher la tâche par `messageId`, rattacher seulement une corrélation identique et
reprendre le polling. Pour une vraie collision, rejeter et ouvrir une nouvelle tentative avec de nouveaux IDs.
Reconstruire une projection depuis l'autorité, avec preview et remplacement atomique.

## Vérification et clôture

Exiger corrélation bijective, états compatibles, digests Evidence valides, zéro doublon et deux fenêtres sans
divergence. Ajouter un cas de régression reproduisant la cause avant réouverture.

## Escalade

Escalader immédiatement à `agent-platform`, Temporal et Sécurité. Si plusieurs tâches sont touchées, appliquer
[Rollback A2A](ROLLBACK-A2A.md).
