# Gate du lot 8 — Worktrees, patches parallèles et intégration

- Date de validation : 2026-09-02
- Verdict : `PASS`
- Critère : aucun agent parallèle ne modifie l'espace d'intégration et aucun conflit n'est résolu silencieusement.

## Invariants vérifiés

- chaque délégation Code reçoit un worktree détaché, distinct et épinglé au commit source vérifié ;
- deux délégations ne sont parallélisées que si leurs scopes d'écriture sont prouvés disjoints ;
- un Developer ou Patch Repair retourne un artefact Evidence immuable, jamais une mutation de l'espace d'intégration ;
- format, taille, chemins, opérations, scope et digest du patch sont revérifiés avant publication ;
- tout fichier commun, hunk incompatible, renommage ou suppression contradictoire produit un conflit explicite ;
- l'ordre d'application est dérivé du DAG validé et digéré indépendamment de l'ordre d'arrivée ;
- seul `PatchIntegrationWorkflow` choisit les profils sandbox et autorise l'application consolidée ;
- `git diff --check`, tests, qualité et sécurité sont rejoués sur le workspace consolidé ;
- Patch Repair est borné à deux essais et à la cause et aux chemins ciblés ;
- l'intégration est bornée à trois essais avant une décision `ESCALATE` ;
- les worktrees et données temporaires non livrables sont nettoyés sur toute issue terminale.

## Preuves automatisées

| Preuve | Résultat |
|---|---|
| `CodeWorkspaceManagerTest` | Vérifie isolation, commit source, disjonction préalable, nettoyage idempotent et refus d'un chemin forgé. |
| `CodeScopePolicyTest` | Vérifie les scopes fichier, répertoire et module ainsi que tous les recouvrements bloquants. |
| `PatchProposalValidatorTest` | Vérifie contenu, digest, taille, chemins, opérations et scope avant publication. |
| `CodePatchArtifactPublisherTest` | Vérifie la publication Evidence sans mutation de l'espace d'intégration. |
| `PatchConflictDetectorTest` | Vérifie fichiers communs, hunks incompatibles, renommages et suppressions contradictoires. |
| `PatchIntegrationPlannerTest` | Vérifie ordre topologique stable, digest rejouable et refus des conflits. |
| `PatchIntegrationWorkflowTest` | Vérifie profils imposés, validations consolidées et nettoyage détaché après succès ou échec. |
| `PatchIntegrationActivitiesImplTest` | Vérifie matérialisation Evidence, application unique, tests/scans consolidés et nettoyage pour les quatre issues terminales. |
| `PatchRepairAgentTest` et `PatchAttemptPolicyTest` | Vérifient ciblage strict, autorisations numérotées, plafonds et escalade. |
| `PatchIntegrationScenariosTest` | Deux patches disjoints atteignent une application ; chevauchement et collision sont arrêtés avant tout appel sandbox. |
| Suite Maven de l'orchestrateur | `PASS` — 298 tests avec `mvn -q -Dmaven.repo.local=/tmp/servicemesh-m2 test`. |

## Conclusion

Les agents Code écrivent uniquement dans leurs worktrees et publient des propositions liées à Evidence. Le
workspace d'intégration n'est modifié qu'une fois, par une activité possédée par le workflow, après validation de
l'ordre et absence de conflit. Toute ambiguïté inter-patches devient une erreur visible, une réparation ciblée et
bornée ou une escalade ; aucune stratégie de fusion silencieuse n'existe dans le chemin d'intégration.
