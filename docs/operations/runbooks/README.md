# Runbooks multi-agents

> Applicabilité : Temporal et les modes hiérarchiques sont désactivés par défaut. Les procédures correspondantes
> décrivent la cible d'exploitation et ne deviennent exécutables qu'après câblage des configurations, identités,
> montages et commandes opérateur. Le kill switch existe dans le code mais son fichier n'est pas monté par le
> Compose courant.

| Situation | Action initiale | Runbook |
|---|---|---|
| Activation ou extension progressive | vérifier prérequis, périmètre et retour arrière | [Canary, kill switch et incident](CANARY-KILL-SWITCH-INCIDENT.md) |
| Violation critique, qualification révoquée ou SLO dépassé | ramener les admissions à `PIPELINE` | [Rollback multi-agents](ROLLBACK-MULTI-AGENTS.md) |
| Boucle, budget, coût ou contrat d'un rôle | isoler le rôle sans élargir ses droits | [Agent défaillant](AGENT-DEFAILLANT.md) |
| Serveur MCP suspect ou preuve altérée | couper serveur/outils et geler les effets | [MCP compromis](MCP-COMPROMIS.md) |
| Backlog ou saturation | suspendre les admissions hiérarchiques | [Saturation](SATURATION.md) |
| Temporal indisponible | préserver l'historique et geler les effets inconnus | [Temporal indisponible](TEMPORAL-INDISPONIBLE.md) |

Tous les runbooks sont fail-closed : ils ne permettent ni de contourner un gate, ni de répéter un effet dont
l'issue est inconnue, ni de supprimer une preuve pour rétablir le service.
