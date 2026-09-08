# A2A-229 — correction du replay inter-canaux des notifications

## Incident

- workflow : `ai-factory/fbece50e/pipeline-1` ;
- run : `01a08063-d355-756f-8e11-186c5712ab5d` ;
- rôle : `architecture-agent` ;
- transition : séquence `2`, état `COMPLETED` ;
- erreur : `Divergent A2A workflow notification replay`.

La réconciliation `tasks/get` avait installé la transition terminale avant le callback. Les deux représentations
différaient uniquement par leur timestamp (`2026-09-08T09:40:49.748548Z` et
`2026-09-08T09:40:49.751606Z`) et leur provenance (`source=getTask` contre metadata vides).
`Notification.equals()` comparait ces champs de transport et produisait donc un faux positif.

## Correction et frontière de sécurité

`A2aTaskAwaiter.sameTransition` compare explicitement `agentRole`, `taskId`, `contextId`, `sequence`, `state` et
les artefacts complets. `occurredAt` et les metadata de notification ne participent pas à l'identité inter-canaux.
Une différence d'état, de contexte, de rôle, de liste, de contenu ou de metadata d'artefact reste rejetée.

`PostgresA2aNotificationInbox` n'est pas modifié : deux callbacks de même séquence restent soumis à la comparaison
stricte de leur digest canonique et une séquence hors ordre reste rejetée avant signalement à Temporal.

## Preuves exécutables

- `A2aTaskAwaiterTest.getTaskThenCallbackWithDifferentTransportFieldsIsAnEquivalentDuplicate` reproduit les
  timestamps et metadata de l'incident ;
- `A2aTaskAwaiterTest.reconciledTerminalTransitionAcceptsTheEquivalentCallbackAndSchedulesFollowUpOnce` couvre le
  workflow Temporal embarqué, compte une seule activité distante et une seule activité suivante, puis rejoue
  l'historique produit avec `WorkflowReplayer` ;
- `A2aTaskAwaiterTest.reconciledTerminalTransitionStillFailsItsWorkflowTaskForDivergentArtifacts` vérifie le rejet
  négatif dans un Workflow Task ;
- `PostgresA2aNotificationInboxTest` couvre le digest identique, le payload modifié et l'ordre des séquences ;
- `WorkflowDeterminismArchitectureTest` rejoue les cinq historiques de référence versionnés.

Commandes exécutées le 2026-09-08 :

```bash
./mvnw -s .mvn/settings-direct.xml test -Dtest=A2aTaskAwaiterTest
./mvnw -s .mvn/settings-direct.xml test \
  -Dtest=PostgresA2aNotificationInboxTest,PostgresA2aNotificationInboxSqlTest,TemporalA2aNotificationReceiverTest
./mvnw -s .mvn/settings-direct.xml test \
  -Dtest=A2aTaskAwaiterTest,WorkflowDeterminismArchitectureTest
./mvnw -s .mvn/settings-direct.xml test
```

Les lots ciblés réussissent respectivement avec 12, 5 et 20 tests verts. La suite complète exécute 564 tests mais
reste rouge sur une assertion préexistante et hors périmètre dans `OpenTelemetryParityTest` (9 dashboards détectés
pour 8 attendus). Deux erreurs de socket dues au sandbox ont été rejouées hors sandbox : les 2 tests concernés sont
verts.

## Limite de la preuve réelle

L'export préalable du run a échoué : Temporal ne retrouve plus le workflow ni le run, et la base de projection
courante ne contient plus la tâche `fbece50e`. Le fichier temporaire d'export est vide et aucun payload d'incident
n'est versionné. Le replay synthétique démontre le comportement corrigé mais ne remplace pas le replay de
l'historique réel ; la livraison et la clôture restent bloquées sur cette preuve.
