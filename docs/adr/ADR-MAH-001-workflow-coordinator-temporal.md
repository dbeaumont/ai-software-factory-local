# ADR-MAH-001 — Spring Boot et Temporal pour le Workflow Coordinator

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : architecture multi-agent hiérarchique, modes shadow, canary et actif

## Contexte

Le pipeline actuel est exécuté dans `TaskService` par une suite d'appels Java asynchrones. Son état est conservé
en mémoire et sa progression dépend de la disponibilité du processus Spring Boot. La cible ajoute des délégations
hiérarchiques, des branches parallèles, des attentes humaines, des budgets cumulés et des reprises après panne.

Le coordinateur doit rester distinct du runtime LLM : le workflow applique les règles et déclenche les effets,
tandis que les agents proposent des plans, patches et évaluations sous contrats.

## Décision

1. Le control plane reste une application Java Spring Boot.
2. Temporal et son SDK Java portent l'exécution durable des workflows.
3. Une tâche de l'usine correspond à un workflow racine `SoftwareFactoryWorkflow`.
4. Une délégation spécialisée correspond à un Child Workflow ou à une Activity typée selon sa durée et son besoin
   d'état propre.
5. Les appels LLM, MCP, stockage et autres I/O sont réalisés dans des Activities ; le code Workflow ne réalise
   aucun I/O direct.
6. Spring AI et LiteLLM restent le runtime d'inférence des agents.
7. Les serveurs MCP restent les seules façades vers le contexte, la sandbox, l'assurance, les preuves et le SCM.
8. Le rôle hôte `workflow` reste seul autorisé à déclencher une action à effet.
9. Une interface applicative `WorkflowCoordinator` masque Temporal afin de conserver un chemin déterministe local
   et de rendre le moteur remplaçable dans les tests.

## Répartition des responsabilités

| Composant | Responsabilité |
|---|---|
| Spring Boot | API, authentification, configuration, projection de lecture, workers et clients MCP. |
| Temporal | Historique durable, ordonnancement, timers, retries, signaux, annulation et reprise. |
| Agent Runtime | Prompts, appels modèle, outils de lecture, validation des contrats et budgets LLM. |
| MCP | Capacités techniques typées, autorisées, bornées et auditées. |
| PostgreSQL métier | Projection interrogeable des tâches, délégations, décisions et coûts. |
| Evidence MCP | Artefacts immuables, manifestes, digests, classification et audit de lecture. |

## Conséquences

- Temporal devient une dépendance d'infrastructure du mode hiérarchique.
- Les Activities à effet doivent être idempotentes et utiliser des identifiants stables.
- Les changements de code Workflow doivent respecter les règles de déterminisme et de versionnement Temporal.
- La projection métier n'est pas la source de reprise du workflow ; l'historique Temporal conserve cette fonction.
- Le pipeline actuel reste accessible derrière le port `WorkflowCoordinator` jusqu'à la fin du canary.

## Alternatives écartées

- **Conserver uniquement un executor Java et une map en mémoire** : reprise, signaux humains et historique durable
  devraient être reconstruits dans le projet.
- **Utiliser Spring Statemachine comme moteur principal** : le projet devrait encore implémenter persistance,
  orchestration distribuée, reprise et gestion des effets longue durée.
- **Utiliser un framework d'agents comme coordinateur global** : cela confondrait raisonnement probabiliste et
  autorité sur les effets déterministes.
- **Créer un microservice par agent** : la séparation logique des rôles ne justifie pas cette complexité de
  déploiement au stade du prototype.

## Critères de vérification

- le port `WorkflowCoordinator` ne dépend pas de l'API Temporal ;
- aucun code Workflow n'appelle directement LiteLLM ou un serveur MCP ;
- les agents ne reçoivent aucun client à effet ;
- le pipeline déterministe reste exécutable pendant toute la migration.
