# A2A-141 — corrélation et cardinalité

## Contrat

- `task.id`, `workflow.id` et `message.id` sont des attributs de span et des champs MDC temporaires ;
- les scopes MDC restaurent toujours le contexte précédent, y compris en cas d'exception ;
- les métriques A2A ne peuvent être construites qu'avec `agent.role`, `agent.skill`, `rpc.operation`,
  `a2a.version` et `task.state` ;
- rôles, opérations, version et états utilisent des ensembles fermés ; les skills sont issus des Agent Cards
  signées et limités à 128 caractères ;
- aucun identifiant de tâche, workflow, run, contexte, message, délégation ou tenant n'est présent dans le type de
  dimensions métriques.

## Validation

- runtime A2A : 56 tests verts, plus 10 tests `agent-core` ;
- orchestrateur : tests ciblés `A2aMetricDimensionsTest`, `A2aW3cTraceContextTest` et
  `TemporalTraceContextPropagatorTest` verts ;
- construction JDK 25 du runtime avec export OTLP activé réussie.
