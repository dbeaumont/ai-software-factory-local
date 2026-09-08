# ADR-MAH-010 — Exécution multi-agent hiérarchique unique

- Statut : accepté
- Date : 2026-09-08
- Portée : nouvelles admissions, routage métier, confinement et rollback
- Remplace : `ADR-MAH-005` et `ADR-MAH-008`

## Contexte

Les modes `PIPELINE`, `HIERARCHICAL_SHADOW`, `HIERARCHICAL_CANARY` et `HIERARCHICAL_ACTIVE` ont permis de
qualifier progressivement l'architecture multi-agent. Le chemin `PIPELINE_BASELINE` a conservé pendant cette
promotion la chaîne historique Planner → Developer → Tester → Reviewer comme référence et solution de repli.

Temporal est désormais le moteur unique d'orchestration conformément à `ADR-TEMP-001`. Maintenir plusieurs modes
métier et un fallback vers la baseline crée une seconde matrice de comportement, conserve des rôles historiques
et rend le confinement moins explicite. La fin de la promotion doit distinguer les chemins normaux de la nouvelle
architecture des mécanismes temporaires utilisés pour sa qualification.

## Décision

Toutes les nouvelles admissions utilisent l'architecture multi-agent hiérarchique. Le choix d'un mode
d'exécution disparaît du nouveau contrat Temporal et des entrées publiques. Le routage déterministe de l'hôte ne
retient que les décisions suivantes :

| Décision | Usage | Exécution |
|---|---|---|
| `SHORT_CODE_PATH` | tâche R0/R1 simple, mono-module et mono-domaine | Supervisor minimal, Code, contrôles déterministes et revue indépendante |
| `HIERARCHICAL_PATH` | tâche complexe, transverse ou comportant un impact matériel | Supervisor et spécialistes requis par le DAG validé |
| `HUMAN_TRIAGE` | données insuffisantes ou contradictoires, budget absent, R3/R4 ou aucune route sûre | aucun effet autonome avant décision humaine |

`PIPELINE_BASELINE` n'est pas un chemin standard de l'architecture multi-agent. Il désigne exclusivement le
parcours historique antérieur et n'est plus autorisé pour une nouvelle admission. Sa suppression ne retire pas
le traitement optimisé des tâches simples, qui reste assuré par `SHORT_CODE_PATH`.

Le comportement hiérarchique est implicite dans le workflow V2. `HIERARCHICAL_ACTIVE` peut uniquement subsister
dans le workflow V1, ses historiques, projections et preuves pendant leur drainage. Aucun ticket, résultat de
modèle ou paramètre d'API ne peut sélectionner un ancien mode.

## Qualification, risque et autorité

La disparition des modes ne diminue aucun garde-fou :

1. l'hôte conserve la qualification, la classification de risque, les plafonds de budget et la validation des
   entrées ;
2. R0 et R1 peuvent être routés automatiquement selon la politique ;
3. R2 exige une décision avant effet externe ;
4. R3 passe par une décision humaine avant Code et avant effet externe lorsque les deux objets diffèrent ;
5. R4 est refusé par l'automatisation ;
6. une donnée absente ou contradictoire et un budget indisponible conduisent à `HUMAN_TRIAGE`.

Le Supervisor propose un DAG dans les limites des politiques, mais ne choisit ni son propre niveau d'autonomie,
ni la classe de risque, ni le franchissement d'une porte humaine.

## Compatibilité Temporal

La suppression des anciens modes s'applique uniquement aux nouvelles admissions :

1. `SoftwareFactoryExecutionWorkflowV1`, son contrat et ses Build IDs compatibles restent disponibles tant que
   des historiques V1 doivent être rejoués ;
2. un nouveau type de workflow V2 porte le contrat sans mode ;
3. les workers V1 et V2 utilisent des Build IDs distincts et ne prennent pas en charge un historique
   incompatible ;
4. les projections historiques restent lisibles pendant leur rétention ;
5. V1 est retiré seulement après drainage et vérification du replay.

## Confinement et rollback

Le rollback fonctionnel vers `PIPELINE`, shadow ou canary est supprimé. En cas d'incident :

1. fermer les nouvelles admissions ;
2. arrêter les nouvelles délégations ;
3. geler les effets externes non confirmés ;
4. réconcilier tout effet à issue inconnue avec son autorité et sa clé d'idempotence ;
5. préserver les historiques Temporal, projections PostgreSQL et références Evidence ;
6. restaurer un build Temporal préalablement qualifié et compatible avec les historiques concernés ;
7. conserver les admissions fermées si aucun build compatible n'est disponible.

Un kill switch confine donc la fabrique ; il ne sélectionne jamais une ancienne stratégie métier.

## Frontières exclues

Les mécanismes `MCP_SHADOW` qualifient une autre frontière technique. Ils ne sont ni renommés ni supprimés par
cette décision sans analyse et ADR dédiées.

## Conséquences

- les modes de promotion cessent d'être des dimensions actives de l'API, des politiques et de la télémétrie ;
- le chemin court et le chemin hiérarchique complet partagent le même modèle d'autorité et de sécurité ;
- Planner et Reviewer historiques peuvent être retirés après drainage de V1 ;
- les opérateurs perdent le fallback pipeline, mais disposent d'un confinement fail-closed et d'un rollback de
  build compatible ;
- les documents actifs ne doivent plus présenter `PIPELINE_BASELINE` comme fallback de
  `HIERARCHICAL_ACTIVE`.

## Vérification

- toute nouvelle admission démarre le workflow V2 sans champ de mode ;
- le routage retourne uniquement `SHORT_CODE_PATH`, `HIERARCHICAL_PATH` ou `HUMAN_TRIAGE` ;
- les risques, budgets, gates humaines et contrôles déterministes restent couverts par les tests ;
- les historiques V1 sont rejoués avec un worker compatible avant son retrait ;
- le kill switch ne déclenche aucun parcours pipeline.
