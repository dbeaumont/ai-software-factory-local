# Runbooks multi-agents

> Applicabilité : Temporal est l'unique coordinateur, y compris pour le pipeline. Il n'existe aucun fallback local.
> Les procédures historiques de canary métier restent utiles pour évaluer une stratégie, mais elles ne permettent
> jamais de réactiver une invocation locale : tous les modes invoquent les agents par A2A.

| Situation | Action initiale | Runbook |
|---|---|---|
| Ajout ou modification d'un rôle | qualifier catalogue, carte, identité, task queue et permissions | [Ajouter un agent A2A](../../architecture/agents/AJOUTER-UN-AGENT-A2A.md) |
| Violation critique, release révoquée ou SLO A2A dépassé | fermer les admissions et redéployer une release A2A compatible | [Rollback A2A](ROLLBACK-A2A.md) |
| Boucle, budget, coût ou contrat d'un rôle | isoler le rôle sans élargir ses droits | [Agent défaillant](AGENT-DEFAILLANT.md) |
| Serveur MCP suspect ou preuve altérée | couper serveur/outils et geler les effets | [MCP compromis](MCP-COMPROMIS.md) |
| Backlog ou saturation globale | suspendre les admissions | [Saturation](SATURATION.md) |
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
| Carte A2A invalide | geler le rôle et vérifier signature, version et ACL | [Carte A2A invalide](A2A-CARTE-INVALIDE.md) |
| Agent A2A indisponible | suspendre les admissions du rôle | [Agent A2A indisponible](A2A-AGENT-INDISPONIBLE.md) |
| Tâche A2A bloquée | réconcilier avant toute relance | [Tâche A2A bloquée](A2A-TACHE-BLOQUEE.md) |
| Callback A2A perdu | conserver le polling borné et réparer la notification | [Callback A2A perdu](A2A-CALLBACK-PERDU.md) |
| Divergence ou collision A2A | geler les effets et comparer les autorités | [Divergence A2A](A2A-DIVERGENCE-ETAT.md) |
| Certificat A2A expiré | isoler le certificat et effectuer une rotation contrôlée | [Certificat A2A expiré](A2A-CERTIFICAT-EXPIRE.md) |
| Saturation A2A | couper les nouvelles admissions du rôle saturé | [Saturation A2A](A2A-SATURATION.md) |
| Régression A2A | redéployer le dernier runtime A2A qualifié | [Rollback A2A](ROLLBACK-A2A.md) |

Tous les runbooks sont fail-closed : ils ne permettent ni de contourner un gate, ni de répéter un effet dont
l'issue est inconnue, ni de supprimer une preuve pour rétablir le service.
