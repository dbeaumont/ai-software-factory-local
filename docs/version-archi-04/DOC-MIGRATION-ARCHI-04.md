# Principes de migration vers une architecture Multi agents hiérarchique

## Conclusion

Le proto n’est pas encore réellement de type 04. Il correspond surtout à l’archétype 02, « pipeline déterministe enrichi par IA », avec plusieurs rôles LLM spécialisés.

Le signe le plus clair est le pipeline entièrement codé et séquentiel dans [TaskService.java](/Users/david/Dev/ai-software-factory-local/apps/orchestrator/src/main/java/com/example/aifactory/service/TaskService.java:129) : Planner → Developer → tests → qualité → sécurité → Reviewer. Aucun superviseur ne décompose dynamiquement la tâche, ne choisit les spécialistes à mobiliser ou n’arbitre leurs contradictions.

Je viserais donc un **multi-agent hiérarchique gouverné** : le superviseur propose la décomposition et la synthèse, tandis que le workflow garde le contrôle des effets, des budgets et des gates.

## Architecture cible du proto

```text
Ticket
  ↓
Workflow déterministe — politiques, budgets, approbations
  ↓
Agent superviseur
  ├── Agent Architecture
  ├── Agent Code A / Code B
  ├── Agent Tests
  └── Agent Sécurité
          ↓
Mémoire de tâche partagée + contrats versionnés
          ↓
Intégration déterministe des patches
          ↓
Tests / qualité / sécurité en sandbox
          ↓
Consolidation et arbitrage par le superviseur
          ↓
Reviewer indépendant → approbation humaine → PR
```

Le workflow resterait seul autorisé à appliquer un patch, lancer les contrôles et créer une PR, conformément à la séparation déjà présente dans [tool-permissions-v1.yaml](/Users/david/Dev/ai-software-factory-local/resources/mcp/policies/tool-permissions-v1.yaml:3).

## Les changements structurants

1. **Ajouter un véritable agent superviseur**

Le Planner actuel deviendrait soit le superviseur, soit un spécialiste Architecture. Le superviseur produirait un graphe de délégation typé :

- sous-tâche et objectif ;
- spécialiste désigné ;
- dépendances avec les autres sous-tâches ;
- périmètre de fichiers ou modules ;
- critères de succès et preuves attendues ;
- budget de tours, tokens, coût et durée ;
- niveau de risque ;
- stratégie d’intégration.

Le modèle proposerait ce graphe, mais le code hôte le validerait : rôles autorisés, DAG sans cycle, fan-out borné, scopes compatibles et budget global suffisant.

2. **Remplacer le pipeline monolithique par un exécuteur de DAG**

`TaskService` deviendrait une façade, avec des composants du genre :

- `WorkflowCoordinator` ;
- `SupervisorAgent`;
- `DelegationScheduler`;
- `AgentRunService`;
- `TaskMemory`;
- `PatchIntegrator`.

Les analyses Architecture, Tests et Sécurité pourraient s’exécuter en parallèle. Plusieurs agents Code ne seraient parallélisés que lorsque leurs périmètres de fichiers sont disjoints.

3. **Formaliser le catalogue de spécialistes**

Je retiendrais au minimum :

| Rôle | Responsabilité | Droits |
|---|---|---|
| Supervisor | Décomposer, router, consolider | Contexte, preuves, délégation |
| Architecture | Impacts, contraintes, compatibilité | Lecture du dépôt |
| Developer | Produire un patch borné | Lecture du dépôt, aucun effet |
| Tester | Concevoir les tests et analyser les résultats | Contexte et preuves |
| Security | Threat model et analyse des findings | Contexte, SBOM et preuves |
| Reviewer | Contrôle final indépendant | Lecture des preuves |
| Workflow | Appliquer, tester, scanner, livrer | Seul rôle à effet |

Les rôles actuels sont déjà une bonne base, mais l’activation agentique est limitée à Planner et Reviewer dans [AgentToolingProperties.java](/Users/david/Dev/ai-software-factory-local/apps/orchestrator/src/main/java/com/example/aifactory/config/AgentToolingProperties.java:12).

4. **Créer une vraie mémoire de tâche partagée**

Aujourd’hui, l’état est un `ConcurrentHashMap` et un objet mutable [TaskState.java](/Users/david/Dev/ai-software-factory-local/apps/orchestrator/src/main/java/com/example/aifactory/model/TaskState.java:12). Pour le type 04, je stockerais durablement :

- `task`, `run`, `delegation`, `agent_run` ;
- décisions et contradictions ;
- artefacts et versions ;
- références de contexte ;
- budgets consommés ;
- preuves et approbations.

Les agents ne s’écriraient pas directement entre eux. Ils publieraient des résultats validés dans cette mémoire via l’orchestrateur. Le `evidence-mcp` déjà présent dans [compose.yaml](/Users/david/Dev/ai-software-factory-local/infrastructure/compose.yaml:213) pourrait devenir le registre immuable des artefacts, complété par une base pour l’état du workflow.

5. **Étendre les contrats d’échange**

Le Planner, le Tester et le Reviewer possèdent déjà des contrats JSON partiels. Il faudrait ajouter des schémas versionnés, par exemple :

- `delegation-plan-v1` ;
- `specialist-task-v1` ;
- `specialist-result-v1` ;
- `patch-proposal-v1` ;
- `contradiction-v1` ;
- `supervisor-decision-v1` ;
- `agent-run-event-v1`.

Chaque résultat devrait porter `task_id`, `agent_run_id`, `parent_run_id`, `source_commit`, scope, statut, références de preuves et digests. Le patch du Developer gagnerait à être transporté comme artefact référencé plutôt que comme grande chaîne libre.

6. **Isoler les travaux parallèles**

Il ne faudrait pas laisser plusieurs agents modifier le même workspace.

Chaque agent Code travaillerait sur un worktree ou snapshot isolé, basé sur le même commit. Un intégrateur déterministe :

- vérifierait les scopes autorisés ;
- détecterait les fichiers touchés par plusieurs patches ;
- appliquerait les patches dans l’ordre du DAG ;
- relancerait les gates sur le résultat consolidé.

Un conflit ne serait jamais résolu silencieusement par le superviseur : il déclencherait une délégation corrective bornée ou une escalade humaine.

7. **Définir l’arbitrage**

Une règle simple pourrait être :

```text
Gate déterministe
  > politique du dépôt
  > preuve signée
  > conclusion convergente des spécialistes
  > synthèse du superviseur
```

Le superviseur ne pourrait donc jamais transformer un échec Sonar, test ou sécurité en succès. Une contradiction non résolue deviendrait une décision humaine explicite. Le Reviewer final devrait rester indépendant du superviseur.

8. **Ajouter budgets et observabilité hiérarchiques**

Les limites actuelles sont essentiellement par invocation. Il faudrait des plafonds cumulés par tâche :

- nombre maximal de délégations et profondeur hiérarchique ;
- concurrence par rôle ;
- tokens, coût et durée globaux ;
- nombre de replans et réparations ;
- annulation en cascade ;
- traces parent/enfant entre superviseur et spécialistes.

L’interface passerait du stepper linéaire à une vue de graphe montrant délégations, agents en cours, preuves, contradictions et coût par branche.

## Ce que je conserverais

Je ne réécrirais pas les serveurs MCP, la sandbox, les quality gates, le kill switch ou l’approbation humaine. Ce sont précisément les bons rails pour une architecture 04. La matrice deny-by-default de [ToolPermissionMatrix.java](/Users/david/Dev/ai-software-factory-local/apps/orchestrator/src/main/java/com/example/aifactory/service/ToolPermissionMatrix.java:39) permet déjà de maintenir les effets du côté du workflow.

Je ne créerais pas non plus un microservice par agent : la hiérarchie est un patron de coordination, pas nécessairement une topologie de déploiement.

## Trajectoire recommandée

1. Introduire contrats, mémoire partagée et graphe de délégation en mode shadow.
2. Autoriser le superviseur à sélectionner des spécialistes en lecture seule.
3. Activer les analyses parallèles et la consolidation.
4. Introduire ensuite plusieurs agents Code sur des scopes disjoints.
5. Qualifier la nouvelle architecture par A/B avant activation générale.

Cette prudence est importante : la dernière campagne a rejeté l’autonomie Planner/Reviewer, avec 0 % de succès des tests côté candidat et +28,19 % de tokens ([rapport MCP-180](/Users/david/Dev/ai-software-factory-local/docs/mcp/MCP-180-rapport-campagne-20260902.md:11)). Je conserverais donc le mode actuel comme chemin de repli et je réserverais initialement la hiérarchie aux changements réellement multi-domaines.

En résumé : **le changement principal n’est pas d’ajouter davantage d’agents, mais d’introduire une délégation typée, une mémoire partagée, un DAG exécutable et un mécanisme explicite de consolidation/arbitrage** — les quatre caractéristiques centrales de l’architecture 04 décrite page 8 du [dossier](/Users/david/Dev/ai-software-factory-local/docs/dossier-architectures-usines-logicielles-ia.pdf).

