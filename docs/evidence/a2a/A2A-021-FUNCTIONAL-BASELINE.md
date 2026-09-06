# A2A-021 — Baseline fonctionnelle avant coupure

## Verdict

La baseline pré-A2A est figée et reproductible. Elle couvre 36 cas fonctionnels répartis entre les chemins courts,
les délégations multi-domaines, les attaques et la reprise après incident. Les sources sont référencées par
SHA-256 dans `resources/a2a/baselines/pre-cutover-functional-v1.json` ; toute dérive fait échouer le vérificateur.

Cette preuve ne prétend pas rendre les sorties textuelles d'un LLM déterministes. Elle fige les verdicts, les
ordres d'agents, les contrats et références d'artefacts, la chronologie du pipeline, ainsi que les consommations
observées par la campagne de référence. La comparaison après migration devra porter sur ces invariants et sur les
seuils opérationnels, pas sur une égalité textuelle des réponses.

## Corpus et verdicts gelés

| Corpus | Cas | Verdicts ou invariants attendus |
|---|---:|---|
| `short-path` | 8 | 8 `SHORT_CODE_PATH`, ordre Supervisor → Developer → Reviewer, sans Architecture ni Security |
| `multi-domain` | 12 | 10 `HIERARCHICAL_PATH`, 2 `HUMAN_TRIAGE`, ordre Architecture → Code → Tests → Security → Reviewer |
| `adversarial` | 8 | 7 `DENY`, 1 `SERIALIZE_OR_REPLAN`, aucun effet externe non autorisé |
| `recovery` | 8 | 8 reprises terminales sans effet dupliqué |

## Artefacts, consommations et chronologie

- Le contrat de sortie exige le plan, le patch, les résultats de tests, le SBOM, les métadonnées d'exécution et
  la revue ; son digest est figé dans le manifeste A2A.
- La campagne appariée de 20 cas totalise 134 365 tokens et 543 041 ms, soit 6 718,25 tokens et 27 152,05 ms en
  moyenne. Le coût reste explicitement indisponible, jamais interprété comme zéro.
- La chronologie de référence va de `CLONING` à `WAITING_APPROVAL` en passant par planification, patch, tests,
  qualité, sécurité et revue. L'ordre exact est scellé dans le manifeste.
- L'artefact JSONL brut de campagne reste la preuve source immuable ; son SHA-256 est contrôlé.

## Qualification du 6 septembre 2026

Les vérifications suivantes ont réussi sur le commit source
`156b4c485a19850afabd5c84fd6ad04c8cf9a34f` :

```shell
ruby scripts/verify-pipeline-baseline.rb
ruby scripts/verify-a2a-functional-baseline.rb
cd apps/orchestrator
./mvnw -q -s .mvn/settings-direct.xml \
  -Dtest=EvaluationSuiteCoverageTest,ShortCodePathPlannerTest,HierarchicalPathPlannerTest,WorkflowRoutingServiceTest,PipelineCompatibilityTest,DelegationSchedulerTest,TemporalFailureModesTest,TemporalFailureClassifierTest,TemporalCascadeCancellationTest,SoftwareFactoryWorkflowTest \
  test
```

Résultat : 43 tests, 0 échec, 0 erreur. Les avertissements et exceptions visibles dans la sortie Temporal sont les
pannes injectées attendues par les tests de retry, de réponse tardive, d'annulation et d'erreur non retryable.
