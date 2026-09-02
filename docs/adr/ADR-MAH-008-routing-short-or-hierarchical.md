# ADR-MAH-008 — Routage entre chemin court et hiérarchique

- Statut : accepté pour le prototype de migration
- Date : 2026-09-02
- Portée : admission d'une tâche et sélection du workflow effectif

## Contexte

L'architecture multi-agent apporte de la valeur pour les changements ambigus ou multi-domaines, mais multiplie
coût et latence sur les demandes simples. Le routage ne peut pas être laissé au Supervisor, car il pourrait
augmenter sa propre autonomie et son budget.

## Décision

Le `WorkflowCoordinator` calcule le chemin effectif avec la politique versionnée
[`resources/multiagents/policies/routing-policy-v1.yaml`](../../resources/multiagents/policies/routing-policy-v1.yaml).
Le modèle peut produire une évaluation structurée des impacts, mais l'hôte valide les faits utilisés et applique
seul la politique.

## Chemins possibles

| Chemin | Usage | Agents principaux |
|---|---|---|
| `PIPELINE_BASELINE` | Mode pipeline, fallback ou cas non qualifié | Planner, Developer, Tester, Reviewer historiques |
| `SHORT_CODE_PATH` | Changement simple et mono-scope | Supervisor minimal, un Developer, contrôles, Reviewer indépendant |
| `HIERARCHICAL_PATH` | Changement multi-module, multi-domaine ou à impacts transverses | Supervisor et spécialistes requis |
| `HUMAN_TRIAGE` | Risque élevé, données insuffisantes ou choix matériel non résolu | Aucun effet avant décision humaine |

## Règles de sélection

1. Le mode global et la qualification bornent d'abord les chemins accessibles.
2. Une classe de risque interdite est envoyée vers `HUMAN_TRIAGE` avant toute génération de patch.
3. `SHORT_CODE_PATH` exige un seul module, un seul domaine, un scope de fichiers borné et aucun indicateur
   transverse sensible.
4. `HIERARCHICAL_PATH` est sélectionné dès qu'au moins un critère hiérarchique explicite est satisfait.
5. En shadow, le pipeline reste autoritatif même si le routage calculé est hiérarchique.
6. En canary, dépôt, bucket stable, classe de risque et plafond budgétaire doivent tous être autorisés.
7. Une donnée de routage absente ou contradictoire n'entraîne jamais une hausse d'autonomie.

## Critères hiérarchiques

- au moins deux modules ou deux domaines impactés ;
- modification d'authentification, autorisation, secrets, IAM ou réseau ;
- migration de données, changement de schéma ou de propriété de données ;
- changement de contrat public ou compatibilité inter-services ;
- dépendance, build, infrastructure ou CI/CD modifiés ;
- scopes Code indépendants justifiant réellement un traitement parallèle ;
- décision matérielle ouverte nécessitant plusieurs analyses spécialisées.

## Données de routage

Les entrées sont petites et structurées : mode maximal, verdict de qualification, dépôt, classe de risque, nombre
de modules et domaines, indicateurs d'impact, disponibilité des preuves et estimation budgétaire. Le ticket brut
ne devient jamais une expression exécutable de politique.

## Audit et évaluation

Chaque décision conserve `policy_id`, version, entrées normalisées, règle gagnante, chemin choisi et raisons. Les
campagnes mesurent les faux positifs hiérarchiques et les faux négatifs ayant utilisé le chemin court.

## Conséquences

- les petites demandes n'acquittent pas systématiquement le coût de tous les agents ;
- la politique peut évoluer indépendamment des prompts ;
- le Supervisor ne peut pas se placer lui-même en mode hiérarchique ;
- toute évolution de seuil nécessite une nouvelle évaluation de routage.
