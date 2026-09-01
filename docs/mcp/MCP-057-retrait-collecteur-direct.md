# MCP-057 — Retrait du collecteur direct de contexte

## Décision

Les campagnes MCP_ACTIVE `20260901-211328` et `20260901-212521` ont validé le contexte MCP sur 20 tâches et trois écosystèmes, d'abord pour Planner puis pour Planner, Developer et PatchRepair. Aucun fallback direct et aucun contrat Planner invalide n'ont été observés.

Le chemin direct est donc retiré :

- suppression de `RepositoryContextService` et de son parcours `Files.walk` ;
- suppression de `RepositoryContextGateway` et des métriques de comparaison runtime ;
- `McpRepositoryContextService` devient l'unique bean `RepositoryContextProvider` ;
- `MCP_ACTIVE` et les trois rôles sont les valeurs par défaut de l'application, de Compose et de `.env.example` ;
- une ancienne configuration `DIRECT` ou `MCP_SHADOW` échoue explicitement lors de la collecte ;
- un rôle absent de l'allow-list échoue au lieu de revenir au filesystem.

Les rapports shadow restent versionnés comme preuves historiques. Le rollback applicatif consiste désormais à redéployer la version antérieure, pas à activer silencieusement un second chemin dans le même binaire.

## Validation

La suite orchestrateur couvre le mode obsolète, le rôle absent, les limites et les résultats structurés du serveur. La suppression ne retire pas encore le volume de workspace de l'orchestrateur : celui-ci reste nécessaire au clone, à la production des artefacts et au partage avec le sandbox jusqu'à MCP-058.
