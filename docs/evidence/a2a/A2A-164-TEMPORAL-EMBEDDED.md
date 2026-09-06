# A2A-164 — Qualification Temporal embarquée

## Résultat

La barrière d'image de l'orchestrateur exécute désormais explicitement les scénarios Temporal nécessaires à la
bascule A2A. Dernière exécution : **103 tests réussis, aucun échec, aucun test ignoré**.

## Couverture

| Risque | Preuve exécutable |
|---|---|
| Attente d'une tâche A2A et signal reçu | `A2aTaskAwaiterTest.workflowAwaitsASignalWithoutBlockingAndDeduplicatesItsSequence` |
| Signal reçu avant l'attente | `SoftwareFactoryWorkflowTest.consumesAnApprovalDeliveredAtomicallyWithWorkflowStartBeforeAwait` |
| Timer virtuel et réconciliation des transitions manquantes | `A2aTaskAwaiterTest.timerFetchesAndAppliesEveryMissingTransitionInSequenceOrder` |
| Retry d'Activity | `TemporalFailureModesTest.retriesAfterTimeoutAndIgnoresTheLateFirstResponse` |
| Timeout ambigu après effet distant | `A2aTemporalAmbiguousDispatchTest.activityRetryReconcilesALostAcknowledgementWithoutSendingTwice` |
| Déduplication de l'effet externe | le même test constate un seul `send` malgré deux tentatives d'Activity |
| Annulation métier et propagation | `A2aCancellationCoordinatorTest` et `TemporalCascadeCancellationTest` |
| `continue-as-new` avec transport d'état borné | `SoftwareFactoryWorkflowTest.boundsEachRunAndCarriesStateAcrossContinueAsNew` |
| Replay des historiques versionnés | `WorkflowDeterminismArchitectureTest.everyVersionedReferenceHistoryReplaysAgainstTheCurrentWorker` |
| Absence d'effets non déterministes dans les workflows | `WorkflowDeterminismArchitectureTest.workflowImplementationsContainNoDirectNondeterministicEffect` |

Le scénario de timeout ambigu simule une perte d'acquittement juste après la persistance de la corrélation A2A.
Temporal rejoue l'Activity ; `reconcileDispatch` retrouve l'association durable, appelle `GetTask` et ne répète pas
le `SendMessage` distant.

## Commande exécutée

```bash
docker compose --env-file .env -f infrastructure/compose.yaml build orchestrator
```

La sélection de tests correspondante est intégrée à l'étape de build de
`apps/orchestrator/Dockerfile` : une image orchestrateur ne peut donc plus être produite si cette qualification
A2A/Temporal échoue.
