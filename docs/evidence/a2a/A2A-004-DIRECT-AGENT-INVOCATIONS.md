# A2A-004 — Retrait des invocations directes d'agents

## Résultat

- Les implémentations historiques `AgentRuntime`, `SupervisorAgent`, `ArchitectureAgents`, `CodeAgent`,
  `DeveloperAgent`, `PatchRepairAgent`, `TestAgents`, `SecurityAgents` et `IndependentReviewerAgent` ne sont plus
  des composants Spring de l'orchestrateur.
- L'activité Temporal historique `InvokeAgent` et ses DTO ont été retirés de `DurableExecutionActivities` ; son
  implémentation ne dépend plus d'`AgentExecutor`.
- Aucun code sous `workflow`, `controller` ou `config` ne référence une implémentation concrète d'agent ou le port
  d'exécution locale.
- Les classes historiques restent temporairement présentes uniquement comme code non activable pour permettre
  le retrait mécanique et contrôlé prévu par A2A-220.

## Vérification

```shell
rg -n '(SupervisorAgent|ArchitectureAgents|CodeAgent|TestAgents|SecurityAgents|IndependentReviewerAgent|DeveloperAgent|PatchRepairAgent|AgentRuntime)' \
  apps/orchestrator/src/main/java/com/example/aifactory/{workflow,controller,config}
rg -n 'AgentExecutor|invokeAgent\(' \
  apps/orchestrator/src/main/java/com/example/aifactory/{workflow,controller,config}
mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-runtime-m2 \
  -DargLine=-javaagent:/tmp/a2a-runtime-m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -pl apps/orchestrator \
  -Dtest=AgentArchitectureRulesTest,DurableExecutionActivitiesTest,TemporalRuntimeConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Les deux recherches sont vides et les tests ciblés passent.
