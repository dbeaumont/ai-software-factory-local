# A2A-037 — règles de dépendances agent/control-plane

Les types transportés entre Temporal et l'exécution agent appartiennent désormais au port `AgentExecutor`.
`DurableExecutionActivities` et son implémentation ne dépendent plus de la classe concrète `AgentRuntime`.

La suite `AgentArchitectureRulesTest` impose les directions suivantes :

- `workflow`, `controller` et `config` ne peuvent importer aucune implémentation concrète d'agent ;
- les implémentations d'agents ne peuvent dépendre des contrôleurs, workflows/projections, clients SCM, sandbox,
  intégration de patch, JDBC ou datasource ;
- l'activité Temporal injecte le port `AgentExecutor`, jamais `AgentRuntime`.

Cette règle prépare le remplacement du port local par le client A2A sans introduire un second chemin de contrôle.
