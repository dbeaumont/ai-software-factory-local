# A2A-171 — Non-régression du mode métier `PIPELINE`

Date : 2026-09-07

## Verdict

- **Statut : réussi**
- **Résultat ciblé :** 22 tests, 0 échec, 0 erreur, 0 test ignoré.
- **Portée :** orchestration Temporal, préparation des entrées agent, échange A2A, validation des preuves et
  consommation des résultats par les activités du pipeline.

## Bascule vérifiée

Le mode `PIPELINE` reste un choix de parcours métier, sans sélectionner de transport agent. Ses cinq opérations
agentiques sont désormais réalisées par des tâches A2A :

| Opération pipeline | Rôle A2A | Contrat d'entrée | Contrat de sortie |
|---|---|---|---|
| planification | `architecture-agent` | `pipeline-agent-task-v1` | `pipeline-agent-result-v1` |
| génération du patch | `developer` | `pipeline-agent-task-v1` | `pipeline-agent-result-v1` |
| réparation du patch | `patch-repair` | `pipeline-agent-task-v1` | `pipeline-agent-result-v1` |
| évaluation des tests | `test-agent` | `pipeline-agent-task-v1` | `pipeline-agent-result-v1` |
| revue indépendante | `independent-reviewer` | `pipeline-agent-task-v1` | `pipeline-agent-result-v1` |

Temporal conserve la décision, l'ordre, les retries et les gates. Le runtime A2A exécute le prompt propre au rôle.
Les entrées et résultats transitent par Evidence avec digest SHA-256, puis sont validés avant projection. Les tests
déterministes, l'application du patch, les contrôles qualité, sécurité et la livraison restent des activités dédiées.

## Garde-fous de non-régression

`PipelineA2aNonRegressionTest` inspecte le code source de production et échoue si :

- un workflow, une activité de contrôle, `TaskService` ou `PipelineStepService` référence un agent in-process ;
- `PipelineStepService` appelle directement `LlmGatewayClient` ;
- le mode métier introduit un sélecteur `DIRECT`/A2A ou un fallback de transport ;
- le registre de workers réenregistre les anciens workflows directs de délégation.

Les tests Temporal confirment également la propagation des artefacts A2A validés dans le résultat de délégation,
la conservation des gates, des annulations et du chemin de livraison.

## Reproductibilité

```bash
mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-runtime-m2 \
  -DargLine=-javaagent:/tmp/a2a-runtime-m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -pl apps/orchestrator \
  -Dtest=PipelineCompatibilityTest,SoftwareFactoryExecutionWorkflowV1Test,SourceResolutionActivitiesTest,\
PipelineA2aNonRegressionTest,A2aDelegationWorkflowTest,A2aActivitiesTest test
```
