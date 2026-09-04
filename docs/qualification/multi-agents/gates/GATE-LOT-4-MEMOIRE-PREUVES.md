# Gate lot 4 — Mémoire de tâche et preuves

- Statut : validé
- Date : 2026-09-02
- Branche : `features/multiagents`

> Portée du verdict : schémas, reconstruction et scénarios automatisés. L'adaptateur actif de `TaskMemory` reste
> en mémoire ; la projection PostgreSQL et l'Evidence MCP ne sont pas intégrés de bout en bout au pipeline public.

## Critère

> Toutes les décisions peuvent être reproduites depuis l'état durable, les contrats et les preuves vérifiées.

## Chaîne de reproduction

| Décision ou fait | Source d'autorité | Projection reconstructible | Preuve automatisée |
|---|---|---|---|
| État et chronologie du workflow | Historique d'événements Temporal | `tasks`, `workflow_runs` | `ProjectionRebuilderTest` |
| DAG, statut et budget des délégations | Entrée et résultat du workflow Temporal | `delegations`, `budget_usage` | `ProjectionRebuilderTest`, `SoftwareFactoryWorkflowTest` |
| Décisions humaines et approbation | Signals et résultat Temporal liés à la tâche/tentative | `decisions`, `approvals` | `SoftwareFactoryWorkflowTest` |
| Décision de politique finale | Contrat `PolicyDecision` inclus dans le manifeste | Métadonnées du manifeste | `EvidenceApprovalGateTest` |
| Plans, évaluations, patches, tests et reviews | Artefacts immuables Evidence MCP | `artifacts`, `evidence_refs` sans contenu brut | `McpEvidenceRepositoryTest`, `EvidenceApprovalGateTest` |
| Tâches terminales du modèle historique | `TaskView` immuable archivée dans Evidence MCP | `tasks`, `legacy_task_imports` | `LegacyTaskMigratorTest` |

La reconstruction est fail-closed : tous les résumés Evidence MCP sont contrôlés avant l'unique appel de
remplacement atomique de la projection. Un URI, digest, statut, périmètre tâche/tentative ou lignage divergent
interrompt l'opération et conserve la projection précédente. Les contenus sensibles ne sont jamais recopiés
dans PostgreSQL.

## Invariants persistants

- chaque enregistrement durable porte `task_id`, `attempt_id` et `source_commit` ;
- les clés étrangères composites empêchent les références entre tentatives ou commits différents ;
- les transitions utilisent un verrou optimiste et une fonction atomique ;
- la projection UI n'expose que des statuts, budgets, compteurs et métadonnées de preuve ;
- une tâche legacy active reste sur le pipeline historique, seules les tâches terminales sont importées ;
- un import legacy divergent est rejeté, tandis qu'un import strictement identique est idempotent ;
- la vue complète d'une tâche legacy n'est relue qu'après vérification du digest Evidence MCP.

## Contrôles exécutés

```text
cd apps/orchestrator
mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test
Résultat : succès

cd apps/mcp/evidence-server
mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test
Résultat : succès
```

Les scénarios couvrent une perte contrôlée de projection, sa reconstruction à l'identique depuis un historique
Temporal réel de test, le rejet d'une preuve altérée, l'import d'une tâche terminée et sa relecture compatible.

## Conclusion

Pour le périmètre local et CI du prototype, une décision consommée par le workflow reste rattachée à son état
Temporal, à son contrat versionné et aux URI/digests Evidence MCP utilisés. PostgreSQL demeure un modèle de
lecture remplaçable. Le gate du lot 4 est donc franchi.
