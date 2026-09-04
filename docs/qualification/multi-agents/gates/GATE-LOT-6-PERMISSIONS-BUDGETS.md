# Gate lot 6 — Permissions, budgets et limites hiérarchiques

- Statut : validé
- Date : 2026-09-02
- Branche : `features/multiagents`

## Critère

> Aucun agent ne peut élargir son autorité, son scope, son budget ou la profondeur de délégation.

Les propositions du modèle restent des données non fiables. L'hôte conserve l'autorité sur le catalogue des
rôles, la matrice d'outils, les scopes de dépôt, les budgets hiérarchiques, les quotas réels, la topologie du
DAG, les limites de concurrence et les coupe-circuits opérationnels.

## Invariants vérifiés

| Frontière | Contrôle hôte | Preuves automatisées |
|---|---|---|
| Autorité | Catalogue fermé de 14 agents, permissions deny-by-default et outils à effet réservés à `workflow` | `AgentRoleIsolationTest`, `ToolPermissionMatrixTest`, `IndependentReviewerBoundaryTest` |
| Scope | Chaque chemin lu ou écrit doit rester sous les racines autorisées ; les outils Context sont minimaux par rôle | `DelegationValidatorTest`, `RepositoryContextToolsTest` |
| Budget agent | Chaque invocation reste sous le plafond versionné du rôle | `HierarchicalBudgetPolicyTest`, `AgentRuntimeTest` |
| Budget enfant | Tours, tokens, coût, délai et appels MCP ne peuvent excéder les valeurs du parent | `DelegationValidatorTest`, `HierarchicalBudgetPolicyTest` |
| Budget périmètre/tâche | Agrégats Architecture, Code, Tests et Sécurité, plafond global et réserve finale non consommable par le travail standard | `HierarchicalBudgetPolicyTest`, `TaskUsageLedgerTest` |
| Consommation réelle | Ledger atomique cumulant toutes les tentatives et tous les transports MCP, retries compris | `TaskUsageLedgerTest`, `AgentToolLoopTest`, `ResilientMcpToolInvokerTest` |
| Topologie | Profondeur maximale 2 et fan-out maximal 4, configurables uniquement à la baisse | `DelegationPolicyPropertiesTest`, `DelegationValidatorTest` |
| Concurrence | Plafonds équitables global, tâche, rôle et serveur, permis conservés jusqu'à l'arrêt réel | `McpClientPropertiesTest`, `ResilientMcpToolInvokerTest` |
| Arrêt | Conditions stables pour succès, budget épuisé, deadline et absence de progression | `AgentToolLoopTest`, `TaskUsageLedgerTest`, `MultiAgentContractValidatorTest` |
| Exploitation | Kill switch global, serveur, outil, rôle, mode et couple rôle/mode, relu à chaud | `OperationalKillSwitchTest`, `ToolPermissionMatrixTest`, `AgentRuntimeTest` |

## Contrôles exécutés

```text
cd apps/orchestrator
mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test
Résultat : succès — 241 tests, 0 échec, 0 erreur, 0 ignoré
```

Les avertissements Temporal visibles pendant la suite correspondent aux scénarios intentionnels de timeout,
retry, annulation et redémarrage. Ils ne représentent pas des échecs de test.

## Conclusion

Un agent peut demander une délégation ou un outil, mais il ne peut modifier aucune des limites appliquées par
l'hôte. Toute identité, capacité, portée, topologie, consommation ou mode absent de la politique est refusé.
Le gate du lot 6 est franchi pour le périmètre local et CI du prototype.
