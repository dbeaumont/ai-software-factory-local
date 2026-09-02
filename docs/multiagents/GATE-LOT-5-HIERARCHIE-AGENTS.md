# Gate lot 5 — Hiérarchie des agents

- Statut : validé
- Date : 2026-09-02
- Branche : `features/multiagents`

## Critère

> Chaque rôle possède un propriétaire, un prompt, un contrat, des outils, des budgets et des tests dédiés.

Le catalogue contient un control plane déterministe `workflow` de niveau A0 et quatorze rôles LLM. Le control
plane possède son propriétaire, ses contrats et ses capacités, mais aucun prompt ni budget LLM par définition.
Les quatorze rôles LLM possèdent chacun un manifeste et un prompt versionnés, un propriétaire, un niveau
d'autonomie, un contrat de sortie, une allowlist d'outils, un budget et une couverture automatisée.

## Couverture de la hiérarchie

| Périmètre | Rôles | Contrats principaux | Preuves automatisées |
|---|---|---|---|
| Coordination | `supervisor` | `delegation-plan-v1`, `supervisor-decision-v1` | `SupervisorManifestTest`, `SupervisorAgentTest`, `DelegationValidatorTest` |
| Architecture | `architecture-agent`, `impact-analysis`, `dependencies-contracts` | `architecture-assessment-v1`, `specialist-result-v1` | `ArchitectureAgentManifestsTest`, `ArchitectureAgentsTest`, `ArchitectureAssessmentContractTest` |
| Code | `code-agent`, `developer`, `patch-repair` | `integration-proposal-v1`, `code-task-v1`, `patch-proposal-v1`, contrats de réparation | `CodeAgentTest`, `DeveloperAgentTest`, `PatchRepairAgentTest`, `PatchScopeValidatorTest` |
| Tests | `test-agent`, `test-design`, `test-evidence` | `test-strategy-v1`, `test-assessment-v1` | `TestAgentsTest`, `TestStrategyValidatorTest`, `TestEvidenceValidatorTest` |
| Sécurité | `security-agent`, `threat-model`, `security-findings` | `security-assessment-v1`, `vulnerability-result-v1` | `SecurityAgentsTest`, `SecurityFindingsInputValidatorTest`, `SecurityDecisionValidatorTest` |
| Revue indépendante | `independent-reviewer` | `independent-review-v1` | `IndependentReviewerAgentTest`, `IndependentReviewerBoundaryTest`, `SoftwareFactoryWorkflowTest` |

Les rôles historiques `planner` et `reviewer` ne font pas partie de la hiérarchie cible : le catalogue les
déclare uniquement comme rôles de compatibilité du mode `PIPELINE`.

## Invariants vérifiés

- le Supervisor ne délègue qu'aux quatre coordinateurs de périmètre déclarés comme ses enfants ;
- chaque coordinateur de périmètre ne délègue qu'à ses propres sous-agents ;
- Developer et Patch Repair proposent des artefacts bornés sans accès direct au sandbox ;
- Test Evidence ne conclut qu'à partir d'exécutions déterministes fournies par le workflow ;
- Security Agent ne peut accepter ou déclasser un risque sans décision de politique URI/digest explicite ;
- Independent Reviewer est enfant du workflow racine, hors de la hiérarchie du Supervisor et sans effet ;
- tous les documents inter-agents passent par les contrats JSON fermés du catalogue local.

## Contrôles exécutés

```text
cd apps/orchestrator
mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test
Résultat : succès — 217 tests, 0 échec, 0 erreur, 0 ignoré
```

Les avertissements observés correspondent aux scénarios de résilience attendus (retries et redémarrages
Temporal) et à l'auto-attachement Mockito sur le JDK courant ; aucun test n'a échoué.

## Conclusion

La hiérarchie cible du lot 5 est représentée dans le catalogue, les manifestes, les prompts et les façades
hôte. Les frontières de responsabilité Architecture, Code, Tests, Sécurité et Revue indépendante sont
contractuelles et testées. Le gate du lot 5 est franchi pour le périmètre local et CI du prototype.
