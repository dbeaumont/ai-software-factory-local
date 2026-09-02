# Gate du lot 2 — Découpage de l'orchestration

Décision technique : **PASS** le 2 septembre 2026.

Le pipeline historique reste accessible par le port `WorkflowCoordinator`. Son implémentation
`DeterministicWorkflowCoordinator` conserve l'ordre des étapes, les sorties exposées, les contrôles
déterministes et la gate humaine avant livraison.

## Preuves

- `TaskServiceBoundaryTest` vérifie que la façade de tâches ne dépend plus de la sandbox, de l'assurance, du
  SCM ou des outils d'agents.
- `RestApiCompatibilityTest` fige les routes, statuts HTTP et champs JSON de l'architecture 02.
- `WorkflowEffectOwnershipTest` réserve au coordinateur les décisions de tests, scans, assurance et SCM et
  place validation/application derrière `PatchIntegrator`.
- `AgentRuntimeBoundaryTest` et `AgentEffectAdapterArchitectureTest` interdisent les adaptateurs à effet dans
  les agents ; `AgentRuntime` refuse également les outils `sandbox.*`, `assurance.*` et `scm.*`.
- `PipelineCompatibilityTest` exécute le pipeline extrait avec des dépendances simulées et compare transitions,
  sorties, preuves, artefacts et effet en attente à
  `resources/multiagents/baselines/pipeline-v1-output-contract.yaml`, dérivé de `v1.1.0-mcp`.
- `PatchIntegratorTest` vérifie l'immutabilité par digest entre validation et application.
- La suite complète `mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test` passe.
- `ruby scripts/verify-pipeline-baseline.rb` confirme les objets Git et digests de la baseline
  `deterministic-pipeline-v1.1.0-mcp`.

Les avertissements produits par les tests de résilience MCP correspondent aux scénarios négatifs attendus
(timeouts, circuit breaker, limite de concurrence et schéma incompatible) ; ils ne constituent pas des échecs.

## Conclusion

Le découpage est validé sans changement du contrat REST ni de la séquence de sortie du pipeline de référence.
Les dépendances à effet sont explicites et testées, ce qui permet d'introduire Temporal au lot 3 sans exposer
ces dépendances aux agents.
