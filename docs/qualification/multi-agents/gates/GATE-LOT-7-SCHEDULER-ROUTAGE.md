# Gate du lot 7 — Scheduler de DAG et routage

- Date de validation : 2026-09-02
- Verdict : `PASS`
- Critère : un même DAG validé produit une séquence de coordination reproductible sous les mêmes événements externes.

## Invariants vérifiés

- le DAG complet est validé avant le premier lancement : lignée, unicité, dépendances, acyclicité et plafonds ;
- seuls les nœuds indépendants sont lancés dans une même vague, avec quatre exécutions au maximum ;
- les vagues et leur consolidation sont ordonnées par priorité puis par identifiant stable ;
- l'ordre de fin physique des Child Workflows ne modifie pas l'ordre consolidé ;
- échec, timeout, annulation et résultat indéterminé bloquent transitivement les dépendants ;
- deux replans Supervisor au maximum sont admis, avec justification et digests vérifiés ;
- boucles, absence de progression et répétition de travail terminé sont refusées ;
- les chemins court, hiérarchique, baseline et triage sont décidés par l'hôte et journalisés.

## Preuves automatisées

| Preuve | Résultat |
|---|---|
| `DelegationSchedulerTest` | Rejoue deux permutations du même DAG avec les mêmes issues externes et obtient exactement la même séquence de lancements, résultats et blocages. |
| `SoftwareFactoryWorkflowTest` | Vérifie Child Workflows, parallélisme borné, Continue-As-New et propagation d'échec dans Temporal. |
| `DelegationReplanPolicyTest` | Vérifie plafond, digests, justification, cycles, progression et répétitions. |
| `ShortCodePathPlannerTest` | Qualifie les huit scénarios obligatoires du chemin court. |
| `HierarchicalPathPlannerTest` | Qualifie les scénarios multi-domaines et le rattachement indépendant du Reviewer. |
| `WorkflowRoutingServiceTest` | Vérifie précédence, faits normalisés, raison, identifiant idempotent et journal d'évaluation. |
| Suite Maven de l'orchestrateur | `PASS` avec `mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test`. |

## Conclusion

La coordination ne dépend ni de l'ordre fourni par le Supervisor, ni de l'ordre de terminaison concurrent. Les
seuls événements externes incorporés sont leurs issues normalisées ; à entrées et issues identiques, la séquence
de coordination et la décision de routage sont identiques.
