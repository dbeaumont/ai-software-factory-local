# Gate lot 3 — Workflow durable Temporal

- Statut : validé
- Date : 2026-09-02
- Branche : `features/multiagents`

> Portée du verdict : implémentation et scénarios automatisés du workflow durable. Ce `PASS` n'indique pas que
> Temporal porte le parcours `POST /api/tasks` dans Compose ; il est désactivé par défaut.

## Critère

> Une tâche complète survit aux redémarrages et conserve une chronologie déterministe et consultable.

## Preuves

| Propriété | Preuve automatisée |
|---|---|
| Workflow racine, Child Workflows et résultat complet | `SoftwareFactoryWorkflowTest` |
| Chronologie stable, Queries et approbation liée au manifeste | `SoftwareFactoryWorkflowTest` |
| Attente durable de sept jours et reprise par Signal | `SoftwareFactoryWorkflowTest#resumesAnApprovalAfterSeveralVirtualDays` |
| Checkpoint et historique borné | `SoftwareFactoryWorkflowTest#boundsEachRunAndCarriesStateAcrossContinueAsNew` |
| Reprise d'une délégation LLM après interruption du worker | `TemporalWorkerRestartResilienceTest` |
| Reprise du même job sandbox par `execution_id` | `TemporalSandboxResumeResilienceTest` |
| Timeout, retry, doublon, réponse tardive et queue indisponible | `TemporalFailureModesTest` |
| Annulation parent, Child Workflow et sandbox | `TemporalCascadeCancellationTest` |
| Absence d'effets non déterministes dans le code Workflow | `WorkflowDeterminismArchitectureTest` |

Les scénarios de reprise exécutent de vraies instances du serveur Temporal de test et de ses workers. Les effets
externes restent simulés afin que les tests soient déterministes et exécutables en CI.

## Contrôles exécutés

```text
mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test
Résultat : succès

docker compose --env-file .env.example -f infrastructure/compose.yaml config --quiet
Résultat : succès

ruby scripts/verify-pipeline-baseline.rb
Résultat : Pipeline baseline deterministic-pipeline-v1.1.0-mcp verified.
```

## Conclusion

Le workflow durable conserve ses identifiants, son état, ses budgets, ses références de preuves et sa
chronologie lors des retries et des changements de run. Les signaux restent liés à la tâche et à la tentative,
et les effets rejouables utilisent une identité déterministe ou un `execution_id`. Le gate du lot 3 est donc
franchi pour le périmètre local et CI du prototype.
