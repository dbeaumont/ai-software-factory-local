# Runbook — tâche A2A bloquée

## Détection

`AiFactoryA2aTaskStuck` se déclenche lorsqu'une tâche non terminale dépasse cinq minutes. Le SLO de terminaison
reste p95 ≤ 30 minutes ; l'alerte précoce sert à diagnostiquer la progression.

## Confinement immédiat

Suspendre les admissions du rôle si plusieurs tâches progressent mal. Ne jamais supprimer la ligne A2A, terminer
manuellement le workflow ou répéter un effet MCP dont l'issue n'est pas confirmée.

## Diagnostic

```bash
make a2a-status
docker compose --env-file .env -f infrastructure/compose.yaml --profile a2a-full logs --tail=200 a2a-developer
docker compose --env-file .env -f infrastructure/compose.yaml logs --tail=200 temporal
```

Depuis l'ID présent dans la trace, comparer état/sequence A2A, historique Temporal, dernier heartbeat d'activité,
appel MCP et artefacts Evidence. Classer : tâche réellement longue, poller absent, activité bloquée, callback
perdu, effet externe inconnu ou divergence.

## Rétablissement

Laisser Temporal appliquer ses timeouts/retries bornés. Si l'effet est inconnu, le réconcilier par clé
d'idempotence. Annuler seulement via l'API normale ; une nouvelle tentative reçoit un nouveau `attemptId` tout en
préservant les preuves déjà publiées.

## Vérification et clôture

La tâche atteint un état terminal cohérent dans Temporal et A2A, les artefacts sont accessibles, le compteur de
doublons reste nul et l'âge maximal retombe sous le seuil pendant deux fenêtres.

## Escalade

Escalader à `agent-platform` après une boucle de confirmation complète. En cas de divergence, appliquer
[Divergence A2A](A2A-DIVERGENCE-ETAT.md), jamais une correction directe en base.
