# A2A-066 — Corrélation métier/protocole durable

## Résultat

- L'identifiant A2A `taskId` reste l'identité de la tâche protocolaire, de sa projection, de son workflow
  `AgentTaskWorkflowV1` et de ses notifications.
- Le `taskId` métier et l'`attemptId` Temporal reçus dans l'extension d'exécution sont persistés séparément dans
  `a2a_agent_task.business_task_id` et `a2a_agent_task.workflow_attempt_id`.
- Une reprise après redémarrage réinjecte ces deux identifiants persistés dans le workflow d'agent ; elle ne les
  reconstruit pas et ne les remplace pas par l'identifiant protocolaire.
- La lecture et l'écriture Evidence utilisent exclusivement le `taskId` métier et l'`attemptId` admis. La projection
  de l'artefact A2A utilise séparément l'identifiant de tâche protocolaire.
- Un résultat d'activité portant un autre `attemptId` termine la tâche en `FAILED` avant toute publication.

## Migration de données

`V006__add_a2a_business_correlation.sql` ajoute les deux colonnes obligatoires. Les éventuelles tâches créées par
une version antérieure conservent leur comportement historique (`business_task_id = task_id`, tentative
`attempt-1`) ; toutes les nouvelles admissions enregistrent les valeurs exactes de l'enveloppe d'exécution.

## Vérification automatisée

```shell
mvn -q -B -s apps/orchestrator/.mvn/settings-direct.xml \
  -Dmaven.repo.local=/tmp/a2a-runtime-m2 \
  -DargLine=-javaagent:/tmp/a2a-runtime-m2/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
  -pl apps/a2a-agent-runtime -am \
  -Dtest=AgentTaskWorkflowV1Test,A2aRecoveryCoordinatorTest,PostgresA2aTaskStoreTest,A2aSendMessageServiceTest,EvidenceArtifactPublisherTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Le test couvre l'admission, la persistance PostgreSQL, la reprise, l'exécution avec un identifiant métier distinct
de l'identifiant A2A et la publication de l'artefact sur la bonne projection protocolaire.
