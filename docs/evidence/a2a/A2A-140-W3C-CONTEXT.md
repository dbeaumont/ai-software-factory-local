# A2A-140 — propagation du contexte W3C

## Chaîne qualifiée

`traceparent` et `baggage` suivent désormais la chaîne suivante :

1. l'instrumentation HTTP OpenTelemetry extrait le contexte à l'entrée API ;
2. `TemporalTraceContextPropagator` le sérialise dans les headers des workflows, child workflows et activités ;
3. l'activité A2A capture le contexte courant et l'ajoute à l'extension
   `https://ai-factory.local/extensions/w3c-trace-context/v1` ;
4. le serveur d'agent refuse une extension absente, forgée, contenant des caractères de contrôle ou dépassant
   1 024 octets ;
5. le contexte validé est porté dans l'entrée de `AgentTaskWorkflowV1` et dans la requête d'exécution ;
6. chaque appel MCP reçoit le même `traceparent` et le même `baggage` avec l'identité du rôle.

Le contexte n'est jamais utilisé comme dimension de métrique. Les valeurs sont bornées et ne sont pas recopiées
dans les journaux applicatifs.

## Validation

- construction Docker `a2a-developer` : 65 tests (`agent-core` + runtime) verts ;
- construction Docker `orchestrator` : compilation JDK 25 et 8 tests de déterminisme Temporal verts ;
- tests négatifs : traceparent invalide, baggage avec saut de ligne, baggage supérieur à 1 024 octets et extension
  absente.
