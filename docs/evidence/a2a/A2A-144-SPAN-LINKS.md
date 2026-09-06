# Preuve A2A-144 — liens de traces Temporal/A2A

Date : 2026-09-06

## Chaîne de liens

```text
workflow racine / activité Temporal
    -- SpanLink temporal-to-a2a --> ai.factory.a2a.dispatch
    -- W3C dans le message A2A --> ai.factory.a2a.server.task
    -- SpanLink dispatch-to-task --> tâche A2A persistée
    -- W3C dans l'input Temporal --> ai.factory.a2a.agent.workflow
    -- SpanLink task-to-agent-workflow --> activités du workflow d'agent
    -- SpanLink task-to-agent-execution --> ai.factory.a2a.agent.execute
```

Les spans de frontière démarrent volontairement une trace autonome avec `setNoParent()` et portent un `SpanLink`
vers le `SpanContext` source. Les identifiants métier (`task`, `workflow`, `message`, rôle et skill) sont des
attributs de span, pas des dimensions de métrique.

Le serveur propage au workflow d'agent le contexte du span représentant la tâche A2A, et non le contexte HTTP
ou l'identifiant métier. Le workflow transmet ce contexte comme donnée déterministe à ses activités.

## Sûreté au replay

`AgentTaskWorkflowV1Impl` ne dépend d'aucune API OpenTelemetry et ne crée aucun span. Les émissions sont limitées :

- à l'implémentation d'activité de dispatch côté orchestrateur ;
- au handler serveur hors workflow ;
- aux activités de projection et au worker d'exécution côté agent.

Le test `A2aSpanLinksTest` vérifie qu'un span possède une trace distincte, exactement un lien vers le trace ID
distant et la relation attendue. Il contrôle aussi statiquement l'absence d'effet de télémétrie dans le workflow.
`WorkflowDeterminismArchitectureTest` rejoue les historiques Temporal de référence et exige toujours zéro span de
workflow dupliqué pendant le replay.

## Vérification

```bash
mvn -q -s /opt/homebrew/Cellar/maven/3.9.16/libexec/conf/settings.xml \
  -pl apps/a2a-agent-runtime,apps/orchestrator -am \
  -Dtest=A2aSpanLinksTest,AgentTaskWorkflowV1Test,A2aActivitiesTest,WorkflowDeterminismArchitectureTest \
  -Dsurefire.failIfNoSpecifiedTests=false clean test
```

Résultat : tests ciblés et replays verts, aucune erreur ni duplication observée.
