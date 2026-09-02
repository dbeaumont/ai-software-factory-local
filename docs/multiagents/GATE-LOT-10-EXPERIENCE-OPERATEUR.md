# Gate du lot 10 — API, interface et expérience opérateur

- Date de validation : 2026-09-02
- Verdict : `PASS`
- Critère : un opérateur peut expliquer la trajectoire complète, les coûts, les preuves et les décisions d'une tâche.

## Trajectoire explicable

- la vue de tâche identifie le mode d'exécution, le run durable, la version du DAG et le budget global ;
- le DAG relie chaque agent à son parent et à ses dépendances, sans supprimer le stepper du pipeline historique ;
- Architecture, Code, Tests, Sécurité et Revue indépendante sont identifiables par texte et couleur ;
- chaque délégation indique état, arrêt, durée, tours, tokens, coût et outils ;
- les délégations Code exposent leurs scopes, fichiers touchés et collisions ;
- les preuves restent limitées à leurs métadonnées, digest et URI autorisée ;
- contradictions, alternatives, arbitrages et demandes humaines sont reliés et visibles ;
- une approbation hiérarchique porte sur l'identifiant et le digest exacts du manifeste affiché ;
- l'opérateur peut annuler, demander la relance d'un échec récupérable ou activer le fallback autorisé.

## Compatibilité et sécurité

- `rest-api-pipeline-v1.1.json` fige les routes, statuts, champs et états consommés par l'architecture 02 ;
- les extensions 1.2 sont additives et les nouveaux champs absents d'un ancien document sont normalisés ;
- aucun contenu de preuve n'est exposé dans `TaskView` ou lu par l'interface ;
- les décisions humaines sont liées au digest et au rôle métier ;
- les commandes de relance et de fallback sont contrôlées côté serveur ;
- un manifeste modifié invalide l'approbation avec HTTP 409 et provoque le rechargement de l'écran.

## Preuves automatisées

| Preuve | Résultat |
|---|---|
| `OperatorExplainabilityGateTest` | Construit une trajectoire hiérarchique complète et vérifie DAG, usages, outils, impact Code, preuve, contradiction, décision et manifeste. |
| `MultiAgentUiContractTest` | Vérifie la présence de chaque vue opérateur et l'absence d'accès au contenu des preuves. |
| `PipelineRestContractTest` | Vérifie le manifeste exécutable de compatibilité REST du pipeline 1.1. |
| `TaskExecutionViewTest` | Vérifie validation, normalisation et projection des métadonnées hiérarchiques. |
| `TaskServiceTest` | Vérifie blocage des décisions ouvertes et liaison de l'approbation au manifeste courant. |
| `RestApiCompatibilityTest` | Vérifie routes et statuts HTTP historiques et nouveaux. |
| `node --check apps/web/app.js` | `PASS`. |
| Suite Maven de l'orchestrateur | `PASS` — 356 tests, 0 échec, 0 erreur. |

## Conclusion

La projection et l'interface permettent de reconstruire qui a fait quoi, dans quel ordre, avec quels outils,
budgets, coûts et preuves, puis de relier les contradictions aux arbitrages humains. Les actions opérateur sont
bornées par le serveur et l'approbation ne peut porter que sur le manifeste effectivement affiché et revu.
