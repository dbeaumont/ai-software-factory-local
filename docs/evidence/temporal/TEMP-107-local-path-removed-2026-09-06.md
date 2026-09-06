# TEMP-107 — Retrait définitif du chemin local

> Résultat : `PASS`
>
> Date : `2026-09-06`
>
> Admissions : fermées (`temporal_cutover`, révision `2`)

## Changements

- suppression de `DeterministicWorkflowCoordinator` et de son pool de threads local ;
- suppression de `AsyncTaskTracer`, exclusivement utilisé par ce coordinateur ;
- suppression du test devenu sans objet du traceur local ;
- raccordement des tests de contrat directement aux étapes métier extraites ;
- ajout d'une preuve d'architecture qui exige l'absence de la classe locale et la présence du composant Temporal.

## Contrôles

| Contrôle | Résultat |
|---|---|
| Implémentations de production de `WorkflowCoordinator` | une seule : `TemporalWorkflowCoordinator` |
| Références de production au coordinateur/traceur local | aucune |
| Flags de sélection `LOCAL`/`TEMPORAL` | aucun |
| Route de fallback vers un moteur local | aucune |
| Compatibilité du contrat pipeline v0.2 | `PASS`, recomposition directe des étapes extraites |
| Tests Java complets | `557` exécutés, `0` échec, `0` erreur, `1` ignoré |
| Gel `temporal-cutover-v1` | `PASS` sur `9698fa30aa16a43af5b2d4f56b82f1953eb95080` |
| Contrôle de diff | `git diff --check` : `PASS` |

La suppression est préparée pendant que les admissions restent fermées. Aucun déploiement n'est revendiqué par ce
ticket : l'image finale doit être reconstruite, qualifiée et explicitement autorisée avant TEMP-106.
