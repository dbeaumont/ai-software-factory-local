# Preuve A2A-145 — dashboard SigNoz A2A

Date : 2026-09-06

## Vue livrée

Le dashboard versionné `AI Factory A2A Fleet` est généré dans
`infrastructure/observability/signoz/dashboards/a2a.json` et provisionné par le job idempotent existant. Ses dix
panneaux couvrent : flotte, tâches actives, backlog, latences client/serveur p95 par rôle et skill, transitions
d'état, erreurs d'authentification/protocole/admission, cartes invalides, divergence Temporal/A2A, retries,
timeouts, réconciliations, polling, notifications et saturation d'admission.

Le compteur `ai.factory.a2a.client.divergences` est incrémenté avant chaque refus d'une association durable,
d'une continuation ou d'une réponse distante divergente.

## Cardinalité et navigation

Les agrégations utilisent exclusivement `agent_role`, `agent_skill`, `rpc_operation`, `a2a_version`, `task_state`
et `result` (plus `le` pour les histogrammes). Les identifiants de tâche, message, workflow, contexte, délégation,
tenant et appelant sont interdits par `check-a2a-signoz-dashboard.py`.

Chaque panneau conserve les pivots vers traces, logs, Temporal UI, API de tâche et runbooks. Le chargeur `.env`
des scripts SigNoz traite désormais les valeurs Docker Compose comme des données et ne les exécute plus comme du
code shell.

## Vérifications

```text
A2A SigNoz dashboard validated: 10 panels, 17 bounded queries.
Six Grafana baselines match SigNoz queries, presentation defaults, search variables and links.
Validated 90 unique SigNoz dashboard and alert queries.
SigNoz telemetry ready: metrics=784 dashboards=8 alerts=21 retention=720h/360h/15d
```

Le bootstrap local a créé `AI Factory A2A Fleet`, mis à jour les sept dashboards existants et s'est terminé avec
le code `0`. Les tests `A2aClientMetricsTest` et `A2aActivitiesTest` sont verts.
