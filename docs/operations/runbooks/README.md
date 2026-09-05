# Runbooks multi-agents

> Applicabilité : Temporal est l'unique coordinateur, y compris pour le pipeline. Il n'existe aucun fallback local.
> Les procédures multi-agents qui parlent de canary restent applicables aux rôles hiérarchiques, pas au raccordement
> Temporal lui-même.

| Situation | Action initiale | Runbook |
|---|---|---|
| Activation ou extension progressive | vérifier prérequis, périmètre et retour arrière | [Canary, kill switch et incident](CANARY-KILL-SWITCH-INCIDENT.md) |
| Violation critique, qualification révoquée ou SLO dépassé | ramener les admissions à `PIPELINE` | [Rollback multi-agents](ROLLBACK-MULTI-AGENTS.md) |
| Boucle, budget, coût ou contrat d'un rôle | isoler le rôle sans élargir ses droits | [Agent défaillant](AGENT-DEFAILLANT.md) |
| Serveur MCP suspect ou preuve altérée | couper serveur/outils et geler les effets | [MCP compromis](MCP-COMPROMIS.md) |
| Backlog ou saturation | suspendre les admissions hiérarchiques | [Saturation](SATURATION.md) |
| Temporal indisponible | préserver l'historique et geler les effets inconnus | [Temporal indisponible](TEMPORAL-INDISPONIBLE.md) |
| Worker Temporal absent ou incompatible | geler les admissions de la file et conserver le build ID | [Worker Temporal défaillant](WORKER-TEMPORAL-DEFAILLANT.md) |
| Rollback de la couche d'exécution | revenir à un build worker compatible, jamais au coordinateur local | [Rollback Temporal](ROLLBACK-TEMPORAL.md) |
| Projection incohérente | preview depuis l'autorité puis remplacement atomique | [Projection incohérente](PROJECTION-INCOHERENTE.md) |
| Effet externe à issue inconnue | réconcilier par clé d'idempotence sans rejouer | [Effet à issue inconnue](EFFET-ISSUE-INCONNUE.md) |
| Backend sandbox indisponible | suspendre les admissions et réconcilier les exécutions | [Sandbox indisponible](SANDBOX-BACKEND-INDISPONIBLE.md) |
| Collector indisponible ou saturé | préserver le métier et borner la perte de télémétrie | [Collector indisponible](COLLECTOR-INDISPONIBLE.md) |
| Télémétrie absente, retardée ou rejetée | localiser la rupture du pipeline OTLP | [Télémétrie absente](TELEMETRIE-ABSENTE.md) |
| Cardinalité, volume ou coût anormal | réduire le signal à la source sans masquer un incident | [Coût d'observabilité](COUT-OBSERVABILITE.md) |
| Régression de la chaîne d'observabilité | revenir atomiquement à la dernière version qualifiée | [Rollback observabilité](ROLLBACK-OBSERVABILITE.md) |

Tous les runbooks sont fail-closed : ils ne permettent ni de contourner un gate, ni de répéter un effet dont
l'issue est inconnue, ni de supprimer une preuve pour rétablir le service.
