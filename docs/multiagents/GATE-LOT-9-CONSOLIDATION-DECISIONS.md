# Gate du lot 9 — Consolidation, contradictions et décisions

- Date de validation : 2026-09-02
- Verdict : `PASS`
- Critère : toute divergence est résolue par une règle traçable ou présentée explicitement à un humain.

## Invariants vérifiés

- l'ordre d'autorité est fixe : gate déterministe, politique, preuve vérifiée, consensus spécialisé, Supervisor ;
- une autorité plus faible ne peut jamais remplacer la décision d'une autorité plus forte ;
- un conflit au niveau d'autorité dominant est escaladé, jamais tranché silencieusement ;
- les contradictions sont détectées entre Architecture, Code, Tests et Sécurité sur des assertions normalisées ;
- chaque contradiction appartient à une taxonomie fermée : fait, scope, risque, test manquant ou recommandation ;
- la résolution automatique est fail-closed et exige une règle classification/autorité explicitement versionnée ;
- une contradiction résoluble par une preuve déclenche une délégation ciblée, bornée et liée à la tentative ;
- les choix produit, architecture, sécurité et données deviennent des demandes humaines liées au digest à décider ;
- le Supervisor ne peut consolider sur un échec de tests, qualité, sécurité ou politique ;
- chaque arbitrage est append-only et consigne entrées, règle, décision, auteur, preuves et digest canonique ;
- la synthèse finale ordonne décisions, risques résiduels et points humains de façon reproductible ;
- l'Independent Reviewer s'exécute après consolidation et avant exposition de l'effet d'approbation ;
- une contradiction ouverte ou un manifeste différent de celui revu bloque l'approbation.

## Preuves automatisées

| Preuve | Résultat |
|---|---|
| `DecisionAuthorityPolicyTest` | Vérifie l'ordre obligatoire, la non-régression d'autorité, la stabilité et l'escalade de même niveau. |
| `CrossPerimeterContradictionDetectorTest` | Vérifie détection croisée, lineage, normalisation, stabilité et absence de faux conflit intra-périmètre. |
| `ContradictionClassifierTest` | Vérifie les cinq classes contractuelles fermées. |
| `DeterministicContradictionResolverTest` | Vérifie la matrice explicite, le défaut `OPEN` et le refus du consensus/Supervisor comme règle automatique. |
| `ContradictionEvidenceDelegatorTest` | Vérifie les routes de preuve, le Child Workflow, les budgets et identités bornés. |
| `HumanDecisionEscalatorTest` | Vérifie domaines propriétaires, options, preuve et liaison au digest. |
| `SupervisorConsolidationGuardTest` et `SupervisorAgentTest` | Vérifient que les quatre gates déterministes dominent toute proposition de consolidation. |
| `ArbitrationRecorderTest` et migration V008 | Vérifient exhaustivité, ordre canonique, idempotence, immutabilité et stockage métadonnées-only. |
| `FinalConsolidationSummaryBuilderTest` | Vérifie contenu final, lineage, tri et digest reproductible. |
| `SoftwareFactoryWorkflowTest` | Vérifie liaison décision/digest/rôle, ordre consolidation/revue/effet et blocage contradiction/manifeste. |
| Suite Maven de l'orchestrateur | `PASS` — 332 tests avec `mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test`. |

## Conclusion

Une divergence ne disparaît jamais dans une synthèse libre du Supervisor. Elle reste ouverte, reçoit une preuve
ciblée, est résolue par une règle versionnée et auditée, ou devient une décision humaine explicitement liée à
l'objet concerné. Le Reviewer indépendant et le gate d'approbation revérifient ensuite que les contradictions
requises sont closes et que le manifeste soumis est exactement celui qui a été revu.
