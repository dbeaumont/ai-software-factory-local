# MCP-056 — Bascule du contexte dépôt par rôle

## Décision de rollout

La bascule reste limitée au POC local. Elle s'appuie sur le gate du lot 0 déjà approuvé par le Product Owner AI Software Factory et le Représentant RSSI, ainsi que sur l'autorisation explicite de David Beaumont du 1er septembre 2026 de poursuivre les tâches MCP et leurs campagnes.

La promotion est progressive :

1. `planner` seul en `MCP_ACTIVE` ;
2. ajout de `developer` après une campagne Planner conforme ;
3. ajout de `patch-repair` après validation des scénarios nécessitant effectivement une réparation.

La variable `AI_FACTORY_MCP_REPOSITORY_CONTEXT_ACTIVE_ROLES` contient l'allow-list séparée par des virgules. Le mode global doit être `MCP_ACTIVE`. Un rôle absent continue d'utiliser le collecteur direct pendant la phase de canary ; un rôle actif échoue fermé si le serveur MCP est désactivé ou indisponible.

## État initial du canary

```dotenv
AI_FACTORY_MCP_REPOSITORY_CONTEXT_MODE=MCP_ACTIVE
AI_FACTORY_MCP_REPOSITORY_CONTEXT_ACTIVE_ROLES=planner
```

`TaskService` collecte séparément le contexte Planner et Developer. Cette séparation évite qu'une promotion du Planner ne modifie implicitement l'entrée du Developer. La compatibilité de l'ancienne méthode `collect()` est conservée pour les autres consommateurs.

Le lanceur `make mcp-active-campaign CAMPAIGN_ARGS=--execute` refuse de démarrer si le mode n'est pas `MCP_ACTIVE` ou si aucun rôle n'est explicitement autorisé. Ses artefacts sont séparés des rapports shadow et ne contiennent ni prompt, ni plan intégral, ni secret.

## Critères de promotion

- 20 tâches représentatives et trois écosystèmes ;
- aucun contrat Planner invalide ;
- aucune lecture directe pour le rôle actif ;
- aucun fallback silencieux en cas d'échec MCP ;
- revue des décisions, fichiers impactés et tests ;
- rollback immédiat par retrait du rôle ou retour à `MCP_SHADOW`.

Les opérations sandbox, qualité, sécurité et SCM ne sont pas créditées à ce gate.

## Résultat du canary Planner

La campagne `20260901-211328` obtient 20/20 plans présents et zéro contrat invalide avec `planner` seul en MCP_ACTIVE. Les journaux prouvent 20 collectes MCP pour Planner et 14 collectes directes pour les 14 plans implémentables ayant atteint Developer. Aucun fallback Planner n'a été observé et la revue sémantique est conforme.

Décision : **GO** pour l'étape suivante avec `planner,developer,patch-repair`.
