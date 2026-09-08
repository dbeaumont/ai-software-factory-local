# Runbook — callback A2A perdu ou retardé

## Détection

`AiFactoryA2aNotificationLate` signale un âge de notification supérieur à 60 secondes. Une livraison épuisée est
également visible dans `ai.factory.a2a.server.notifications.failed`.

## Confinement immédiat

Conserver la réconciliation par polling borné. Ne pas marquer la tâche terminée à partir du callback seul et ne
pas désactiver HMAC, mTLS, contrôle de séquence ou protection anti-rejeu.

## Diagnostic

```bash
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 orchestrator
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full logs --tail=200 a2a-developer
./scripts/check-signoz-telemetry.sh
```

Comparer sequence, timestamp, signature, URL de callback, DNS/TLS, code HTTP, retries et état courant obtenu par
`tasks/get`. Un doublon inter-canaux est normal lorsque `agentRole`, `taskId`, `contextId`, `sequence`, `state` et
les artefacts complets sont identiques. `occurredAt` et la provenance dans les metadata peuvent différer entre le
task store, la réconciliation `tasks/get` et l'outbox ; ces deux écarts ne suffisent pas à établir une divergence.

## Rétablissement

Réparer le transport ou la clé HMAC, puis laisser l'outbox durable reprendre. Si les retries sont épuisés,
réconcilier par `tasks/get`; ne créer aucune nouvelle tâche. La projection Temporal n'accepte qu'une sequence plus
récente et une corrélation inchangée. Ne pas recréer une tâche distante déjà retrouvée en état terminal.

## Vérification et clôture

Tester notification valide, doublon et ordre inversé ; vérifier le retour du polling au repos, l'outbox vide et
deux fenêtres sans notification tardive. Conserver trace, sequence et digest dans le dossier d'incident.

## Escalade

Escalader à `agent-platform`; impliquer Sécurité en cas d'échec de signature ou de tentative de rejeu.
