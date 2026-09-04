# Gate du lot 1 — Contrats inter-agents

## Verdict

`PASSED` le 2 septembre 2026 sur la branche `features/multiagents` au commit parent `fdd8fe8`.

## Preuves

- 15 contrats versionnés dans `resources/multiagents/schemas`, tous fermés à la racine ;
- catalogue multi-agent référencé par le catalogue MCP ;
- validation hôte par `MultiAgentContractValidator` ;
- liaisons à la tâche, tentative, rôles et références autorisées ;
- validation déterministe des cycles, orphelins, critères de succès et conditions d'arrêt ;
- politique N/N-1 et migrations additives ;
- 15 fixtures golden et tests négatifs, fuzzés et surdimensionnés.

Commande de vérification :

```shell
cd apps/orchestrator
mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 \
  -Dtest=MultiAgentContractValidatorTest,DelegationPlanValidatorTest test
```

Le passage de cette gate autorise le découpage interne du monolithe. Il n'autorise ni activation du mode
hiérarchique, ni effet externe, ni contournement de la validation Produit/Sécurité du lot 0.
