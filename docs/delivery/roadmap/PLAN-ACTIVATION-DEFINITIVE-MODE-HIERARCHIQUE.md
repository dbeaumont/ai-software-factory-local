# Plan d'action — activation définitive du mode hiérarchique

## Objectifs

- [ ] Activer définitivement l'architecture multi-agent hiérarchique pour toutes les nouvelles tâches, qu'elles
  empruntent le chemin court ou le chemin hiérarchique complet.
- [ ] Supprimer les modes et chemins actifs ou de compatibilité `PIPELINE`, `HIERARCHICAL_SHADOW`,
  `HIERARCHICAL_CANARY` et `PIPELINE_BASELINE`.
- [ ] Conserver la compatibilité de replay des historiques Temporal existants pendant leur drainage.
- [ ] Remplacer le rollback fonctionnel vers `PIPELINE` par un confinement fail-closed et un rollback de build.
- [x] Corriger `TASK-MEMORY-EVIDENCE-OPERATIONS.md` afin qu'il décrive Temporal, PostgreSQL et Evidence MCP tels
  qu'ils fonctionnent actuellement.
- [x] Ne pas confondre les anciens modes hiérarchiques avec les mécanismes `MCP_SHADOW`, qui appartiennent à une
  autre frontière et doivent faire l'objet d'une décision séparée.

## Écart bloquant découvert pendant l'exécution

La livraison A reste interdite tant que les trois points suivants ne sont pas résolus :

- [x] Remplacer dans `SoftwareFactoryExecutionWorkflowV2Impl` la délégation interne vers
  `SoftwareFactoryExecutionWorkflowV1Impl` par une orchestration V2 native. Les frontières V1 et V2 utilisent un
  moteur durable neutre commun ; V2 ne dépend plus de la classe workflow V1 et réutilise la source déjà attestée.
- [x] Raccorder `WorkflowRoutingService` au chemin de production ; la décision est exécutée par une activité
  Temporal après attestation du commit source et conservée dans l'historique V2.
- [x] Ajouter à la frontière d'admission une source vérifiable pour les faits requis par le routage
  (`qualification`, risque, modules, domaines, fichiers estimés, scopes indépendants, impacts, contradiction et
  budget). `TaskRequest`, l'API, l'interface et les scripts actifs transportent désormais explicitement ces faits.

Ces corrections ferment le blocage d'admission découvert pendant l'exécution. La promotion reste néanmoins soumise
aux critères de retrait des contrats de compatibilité, aux E2E des deux chemins et aux validations finales ci-dessous.

- [x] Matérialiser les entrées `specialist-task-v1` du chemin complet dans Evidence avant toute délégation A2A,
  avec validation de contrat et liaison à la tâche, à la tentative et au commit source.
- [x] Valider côté hôte les résultats spécialistes relus depuis Evidence et projeter
  l'`integration-proposal-v1` du Code Agent comme plan d'exécution de la phase Code.

## Terminologie et périmètre de la suppression

- [x] Distinguer dans le code et la documentation les modes d'exécution historiques (`PIPELINE`,
  `HIERARCHICAL_SHADOW`, `HIERARCHICAL_CANARY`, `HIERARCHICAL_ACTIVE`) des décisions de routage
  (`PIPELINE_BASELINE`, `SHORT_CODE_PATH`, `HIERARCHICAL_PATH`, `HUMAN_TRIAGE`).
- [x] Documenter `PIPELINE_BASELINE` comme le chemin historique Planner → Developer → Tester → Reviewer, antérieur
  à l'architecture multi-agent hiérarchique et conservé uniquement pour migration, comparaison et rollback.
- [x] Documenter `SHORT_CODE_PATH` comme le chemin standard optimisé de la nouvelle architecture pour les tâches
  simples, avec plan minimal du Supervisor, Developer borné, contrôles déterministes et revue indépendante.
- [x] Documenter `HIERARCHICAL_PATH` comme le chemin complet de la nouvelle architecture pour les tâches complexes
  ou transverses.
- [x] Ne pas assimiler la suppression de `PIPELINE_BASELINE` à la suppression du traitement optimisé des tâches
  simples : ce traitement reste assuré par `SHORT_CODE_PATH`.

## Point de vigilance sur `TaskMemory`

- [x] Confirmer que `TaskMemory` reste le port interne utilisé par `TaskService`, `TemporalAdmissionReconciler` et
  `PipelineExecutionActivitiesImpl`.
- [x] Confirmer que `PostgresTaskMemory` est l'implémentation Spring active de ce port.
- [x] Confirmer que `InMemoryTaskMemory` est limité aux tests et aux outils de migration historiques.
- [x] Corriger la documentation en supprimant l'affirmation selon laquelle l'adaptateur mémoire est actif.
- [x] Ne pas supprimer toute mention de `TaskMemory` sans ouvrir un chantier distinct de renommage ou de
  suppression du port applicatif.

## 0. Figer la décision d'architecture

- [x] Créer une ADR consacrant le parcours hiérarchique comme unique parcours des nouvelles exécutions.
- [x] Décider si le concept de mode disparaît complètement ou reste temporairement représenté par
  `HIERARCHICAL_ACTIVE`.
- [x] Conserver comme décisions de routage métier uniquement :
  - [x] `SHORT_CODE_PATH` ;
  - [x] `HIERARCHICAL_PATH` ;
  - [x] `HUMAN_TRIAGE`.
- [x] Décider que `PIPELINE_BASELINE` ne constitue plus un chemin autorisé ni un fallback.
- [x] Remplacer explicitement les décisions de `ADR-MAH-005` et `ADR-MAH-008` qui maintiennent la baseline ou un
  chemin de secours `PIPELINE`.
- [x] Résoudre l'incohérence actuelle entre `ADR-MAH-005`, qui mentionne encore un chemin de secours contrôlé en
  `HIERARCHICAL_ACTIVE`, et `routing-policy-v1.yaml`, qui n'autorise déjà plus `PIPELINE_BASELINE` dans ce mode.
- [x] Définir le confinement cible : fermeture des admissions, gel des effets externes et réconciliation des effets
  à issue inconnue.
- [x] Définir le rollback cible comme la restauration d'un build Temporal compatible, sans réactivation implicite
  d'un ancien mode métier.
- [ ] Faire approuver l'ADR par Architecture, Exploitation et Sécurité.

### Critères de sortie du lot 0

- [x] La cible ne contient qu'un seul parcours d'exécution autoritatif.
- [x] Le traitement des workflows historiques est explicitement séparé du traitement des nouvelles admissions.
- [x] Le rollback ne dépend plus de `PIPELINE`, `SHADOW` ou `CANARY`.

### Commit proposé

```text
docs(architecture): adopt hierarchical-only execution
```

## 1. Inventorier et préserver les historiques Temporal

- [x] Fermer temporairement les admissions pendant l'inventaire initial.
- [x] Lister tous les workflows ouverts dans le namespace Temporal actif.
- [x] Relever pour chaque workflow : _(aucun workflow présent dans le namespace)_
  - [x] le workflow ID et le run ID ;
  - [x] le type de workflow ;
  - [x] le Build ID ;
  - [x] la valeur de `AiFactoryExecutionMode` ;
  - [x] la phase courante ;
  - [x] les effets externes en attente ou à issue inconnue.
- [x] Rechercher dans les payloads et attributs persistés les valeurs : _(aucun historique ni projection présent)_
  - [x] `PIPELINE` ;
  - [x] `HIERARCHICAL_SHADOW` ;
  - [x] `HIERARCHICAL_CANARY`.
- [x] Exporter hors des sources versionnées un échantillon représentatif des historiques concernés. _(Sans objet :
  le namespace ne contient aucun historique à exporter.)_
- [x] Rejouer les historiques avec le worker actuellement compatible. _(Les huit fixtures versionnées réussissent.)_
- [x] Déterminer la date de drainage ou d'expiration du dernier historique V1. _(Aucun historique V1 ; aucune
  contrainte de drainage au 2026-09-08.)_
- [x] Archiver l'inventaire, les commandes et les résultats sans secret ni payload inutile.
- [x] Rouvrir les admissions seulement après vérification de l'état initial.

### Critères de sortie du lot 1

- [x] Aucun historique existant ne sera routé vers un worker incompatible.
- [x] Les Build IDs à conserver pendant le drainage sont identifiés.
- [x] La date minimale de retrait de la V1 est documentée.

## 2. Introduire une frontière Temporal compatible

- [x] Conserver le workflow V1 et son ancien contrat tant que des historiques compatibles existent.
- [x] Introduire un type de workflow V2 réservé aux nouvelles admissions hiérarchiques.
- [x] En V2, rendre le comportement hiérarchique implicite.
- [x] Supprimer `WorkflowExecutionMode` du nouveau contrat V2.
- [x] Ne plus sérialiser un choix entre `PIPELINE` et `HIERARCHICAL_ACTIVE` dans les nouvelles entrées de workflow.
- [x] Définir un attribut de recherche stable, par exemple `HIERARCHICAL`, si l'exploitation en a besoin. _(Le type
  immuable `SoftwareFactoryExecutionWorkflowV2` suffit à identifier cette architecture ; aucun attribut constant
  supplémentaire n'est persisté.)_
- [ ] Enregistrer V1 et V2 avec des Build IDs Temporal distincts.
- [x] Router toutes les nouvelles tâches vers V2 dans `TemporalWorkflowCoordinator`.
- [x] Empêcher le worker V2 de prendre en charge un historique V1 incompatible. _(Le nouveau registre n'enregistre
  que l'implémentation du type V2.)_
- [ ] Conserver le worker V1 uniquement pour le drainage.
- [x] Ajouter les tests de sérialisation et de validation du contrat V2.

### Critères de sortie du lot 2

- [x] Toute nouvelle admission démarre le workflow V2 hiérarchique.
- [x] Les historiques V1 continuent à être rejoués sans non-déterminisme.
- [x] Aucun nouveau workflow ne persiste une ancienne valeur de mode.

### Commit proposé

```text
feat(orchestrator): route new tasks to hierarchical workflow v2
```

## 3. Simplifier le routage métier

- [x] Retirer le paramètre `mode` de `WorkflowRoutingService.Input`.
- [x] Supprimer la branche `PIPELINE` de `WorkflowRoutingService`.
- [x] Supprimer la branche `HIERARCHICAL_SHADOW` de `WorkflowRoutingService`.
- [x] Supprimer les conditions propres à `HIERARCHICAL_CANARY`.
- [x] Conserver la validation des entrées requises.
- [x] Conserver le contrôle de qualification.
- [x] Conserver le contrôle du budget.
- [x] Conserver la classification du risque et les décisions humaines obligatoires.
- [x] Sélectionner uniquement `SHORT_CODE_PATH`, `HIERARCHICAL_PATH` ou `HUMAN_TRIAGE`.
- [x] Supprimer `requestedMode` et `effectiveMode` des nouvelles décisions si ces champs deviennent constants.
- [x] Prévoir une migration additive de projection si des champs de mode sont actuellement persistés. _(Sans objet :
  `RoutingDecisionJournal` est en mémoire et aucune colonne de décision de routage ne persiste ces champs.)_
- [x] Conserver la lecture des anciennes projections jusqu'à la fin de leur rétention. _(Les projections de tâche
  restent inchangées dans ce lot.)_

### Critères de sortie du lot 3

- [x] Aucun appelant ne peut choisir un ancien mode.
- [x] Une tâche simple reste exécutée dans la nouvelle architecture via `SHORT_CODE_PATH`, sans appel aux rôles
  historiques Planner et Reviewer.
- [x] Une tâche complexe ou transverse reste exécutée via `HIERARCHICAL_PATH`.
- [x] Une entrée incomplète, contradictoire ou sans budget produit toujours un triage humain fail-closed.
- [x] Les risques R3 et R4 conservent leurs décisions humaines ou refus obligatoires.

### Commit proposé

```text
refactor(orchestrator): remove legacy execution-mode routing
```

## 4. Nettoyer les contrats et politiques

- [x] Mettre à jour `routing-policy-v1.yaml` :
  - [x] supprimer `modeCeilings` ;
  - [x] supprimer `PIPELINE_BASELINE` ;
  - [x] supprimer les contraintes propres au canary ;
  - [x] conserver qualification, risque, budget et triage humain.
- [x] Mettre à jour `risk-policy-v1.yaml` :
  - [x] supprimer les matrices par mode ;
  - [x] conserver `AUTO`, `HUMAN_BEFORE_CODE`, `HUMAN_BEFORE_EXTERNAL_EFFECT` et `DENY`.
- [x] Mettre à jour `rollback-policy-v1.yaml` :
  - [x] supprimer `safeMode: PIPELINE` ;
  - [x] supprimer le retour obligatoire par `HIERARCHICAL_SHADOW` ;
  - [x] définir `FREEZE_ADMISSIONS` comme état de sécurité ;
  - [x] conserver la réconciliation par clé d'idempotence.
- [x] Mettre à jour `delegation-plan-v1.schema.json` :
  - [x] supprimer le champ `mode`, de préférence ;
  - [ ] ou limiter temporairement sa valeur à `HIERARCHICAL_ACTIVE` pendant la transition.
- [x] Mettre à jour les golden contracts et fixtures correspondants.
- [x] Retirer les rôles ou alias de compatibilité `planner` et `reviewer` après vérification de leurs consommateurs.
  _(Les capacités actives utilisent `architecture-agent`, `test-agent` et `independent-reviewer` ; les manifestes,
  permissions MCP, schémas d'enveloppe et valeurs par défaut des anciens alias ont été retirés.)_
- [x] Vérifier séparément les usages de `MCP_SHADOW` avant toute suppression. _(Frontière MCP indépendante,
  inchangée par ce lot.)_

### Critères de sortie du lot 4

- [x] Aucune politique active ne propose un ancien mode ou un fallback pipeline.
- [x] Les schémas refusent les anciennes valeurs pour toute nouvelle donnée.
- [x] Les anciennes données restent lisibles par le chemin de compatibilité V1.

### Commit proposé

```text
refactor(policies): collapse execution modes to hierarchical routing
```

## 5. Nettoyer les reliquats applicatifs

- [x] Supprimer les validations multi-modes dans `TaskState`.
- [x] Supprimer la valeur initiale `PIPELINE` de `TaskState`.
- [x] Retirer les anciens modes de `OperationalKillSwitch`.
- [x] Retirer `HIERARCHICAL_SHADOW` de `AgentAbEvaluator` si aucune campagne active ne l'utilise encore.
- [x] Retirer `HIERARCHICAL_SHADOW` des modes acceptés par `AgentLoop`.
- [x] Retirer le fallback `PIPELINE` de `ValidatedMcpToolInvoker`.
- [x] Mettre à jour les métriques et le contrat de télémétrie des modes d'exécution.
- [x] Remplacer le libellé `PIPELINE` dans l'interface web.
- [x] Supprimer les variables de configuration exclusivement liées au shadow ou au canary hiérarchique. _(Aucune
  variable correspondante n'était câblée dans la configuration active.)_
- [x] Supprimer les commandes Makefile exclusivement liées à cette promotion. _(Les cibles de baseline, gel,
  qualification et surveillance `temporal-cutover-*` ont été retirées ; admissions, sauvegarde et restauration
  restent des opérations permanentes.)_
- [x] Supprimer les scripts exclusivement liés à cette promotion. _(`qualify-temporal-cutover.sh`,
  `verify-temporal-cutover-freeze.rb` et `monitor-temporal-cutover.sh` ont été retirés.)_
- [x] Conserver les preuves historiques sous `docs/archive/`. _(Les plans achevés Temporal/A2A et les preuves de
  cutover Temporal, y compris l'inventaire V1 préalable à cette activation, sont archivés et indexés.)_
- [ ] Vérifier avec `rg` que les anciens termes ne subsistent dans aucun chemin actif.

### Critères de sortie du lot 5

- [ ] `PIPELINE`, `HIERARCHICAL_SHADOW`, `HIERARCHICAL_CANARY` et `PIPELINE_BASELINE` ne subsistent que dans les
  archives, preuves historiques et code V1 temporairement conservé pour replay.
- [ ] Aucun fallback silencieux n'est possible.
- [ ] L'interface et la télémétrie présentent le parcours hiérarchique réel.

### Commit proposé

```text
refactor(factory): remove shadow canary and pipeline remnants
```

## 6. Adapter le kill switch et le rollback

- [x] Remplacer `modes.disabled` par un commutateur global d'admission ou d'exécution.
- [x] Prévoir des commutateurs ciblés par rôle ou capacité si nécessaire.
- [x] Fermer les nouvelles admissions lors d'un incident bloquant.
- [x] Arrêter les nouvelles délégations hiérarchiques.
- [x] Geler les effets externes non confirmés.
- [x] Réconcilier les effets à issue inconnue avant toute répétition.
- [x] Préserver les historiques Temporal, projections et Evidence.
- [x] Restaurer un build compatible lorsque le code courant ne peut pas reprendre un historique.
- [x] Interdire tout fallback automatique vers un ancien mode métier.
- [x] Mettre à jour `ROLLBACK-MULTI-AGENTS.md`.
- [x] Mettre à jour `CANARY-KILL-SWITCH-INCIDENT.md` ou l'archiver si son contenu n'est plus applicable.
- [x] Mettre à jour la documentation du cycle de vie des agents.

### Critères de sortie du lot 6

- [x] Le kill switch bloque proprement la fabrique sans exécuter un ancien pipeline.
- [ ] Le rollback d'un build est testé avec un historique réel ou une fixture versionnée représentative.
- [x] Aucun effet externe n'est dupliqué pendant le confinement ou la reprise. _(La réconciliation A2A consulte
  l'association durable ou la tâche distante avant toute nouvelle émission.)_

## 7. Ajouter et adapter les tests

### Tests unitaires

- [x] Vérifier que toute nouvelle admission utilise la V2 hiérarchique.
- [x] Vérifier qu'aucun contrat public n'accepte `PIPELINE`, `HIERARCHICAL_SHADOW` ou `HIERARCHICAL_CANARY`.
- [x] Tester la sélection de `SHORT_CODE_PATH`.
- [x] Vérifier que `SHORT_CODE_PATH` ne sélectionne ni `PIPELINE_BASELINE`, ni les rôles historiques Planner et
  Reviewer.
- [x] Tester la sélection de `HIERARCHICAL_PATH`.
- [x] Tester la sélection de `HUMAN_TRIAGE`.
- [x] Vérifier les décisions automatiques des risques R0 et R1.
- [x] Vérifier l'approbation avant effet externe pour R2.
- [x] Vérifier le triage ou le refus pour R3 et R4.
- [x] Vérifier le comportement fail-closed en cas de budget absent ou d'entrée contradictoire.
- [x] Vérifier que le kill switch refuse toute nouvelle exécution.

### Tests Temporal

- [x] Rejouer les historiques V1 avec le worker V1.
- [x] Rejouer les historiques V2 avec le worker V2. _(Une histoire V2 `HUMAN_TRIAGE`, incluant résolution source
  et décision de routage, est capturée puis rejouée par le test Temporal.)_
- [x] Exécuter le chemin V2 `SHORT_CODE_PATH` jusqu'à l'approbation et la livraison dans l'environnement Temporal
  de test avec les workflows enfants A2A natifs `supervisor`, `developer` et `independent-reviewer`, sans agent
  Architecture, Test ou Sécurité ; le plan Supervisor et la tâche Developer sont matérialisés et validés dans
  Evidence, tandis que les tests, la qualité et la sécurité restent des contrôles déterministes.
- [x] Raccorder au chemin V2 `HIERARCHICAL_PATH` les workflows enfants A2A natifs `architecture-agent`,
  `code-agent` et `security-agent`, avec entrées/résultats Evidence validés et contrôles déterministes conservés.
- [x] Remplacer l'étape de compatibilité Test du chemin complet par la hiérarchie A2A native
  `test-agent -> test-design` : stratégie validée avant l'exécution déterministe, puis évaluation validée et
  projetée à partir des preuves de test.
- [x] Exécuter la revue indépendante du chemin complet via son workflow enfant A2A dédié, sur un manifeste
  hiérarchique immuable lié au patch, aux contrôles déterministes et aux résultats spécialistes validés ; l'accord
  humain final porte sur ce même manifeste.
- [x] Remplacer la génération de patch Developer de compatibilité sur le chemin complet par un à quatre workflows
  enfants A2A `developer.code-task-v1`, dont les scopes sont résolus depuis l'analyse Architecture, les entrées
  sont matérialisées dans Evidence et les propositions sont vérifiées puis projetées avant le sandbox.
- [x] Remplacer le fallback de réparation de patch V2 par un workflow enfant A2A natif `patch-repair`, avec le
  candidat rejeté et son diagnostic bornés dans `patch-repair-task-v1`, puis validation du contenu, du digest et
  du scope de `patch-repair-proposal-v1` avant une nouvelle tentative sandbox.
- [x] Vérifier qu'un worker V2 ne prend pas un historique V1 incompatible.
- [ ] Tester le redémarrage de l'orchestrateur.
- [ ] Tester une rotation de Build ID.
- [x] Vérifier l'absence de duplication de tâches A2A.
- [x] Vérifier l'absence de duplication des effets SCM.

### Tests bout en bout

- [ ] Exécuter un ticket simple utilisant `SHORT_CODE_PATH`.
- [ ] Exécuter un ticket transverse utilisant `HIERARCHICAL_PATH`.
- [ ] Exécuter un ticket sensible passant par une décision humaine.
- [ ] Tester l'annulation puis la reprise.
- [ ] Tester la panne et la reprise d'un agent.
- [ ] Tester la panne et la reprise de Temporal.
- [ ] Tester la restauration du build précédent.
- [ ] Vérifier qu'une nouvelle tâche ne contient aucune ancienne valeur de mode dans ses traces ou projections.

### Validations globales

- [x] Exécuter la suite orchestrateur. _(596 tests réussis, 0 échec, 0 erreur et 1 test ignoré hors sandbox le
  2026-09-09.)_
- [x] Exécuter la suite du runtime agent.
- [x] Exécuter les tests de schémas et politiques.
- [x] Exécuter les replays Temporal versionnés.
- [x] Exécuter les contrôles Compose et A2A. _(`make test` réussit, y compris persistance, PKI, secrets,
  supply-chain, profils et topologies A2A.)_
- [ ] Exécuter les contrôles de formatage et d'analyse statique.
- [x] Vérifier `git diff --check`.
- [ ] Examiner le diff afin d'exclure toute modification sans rapport.

### Commit proposé

```text
test(factory): qualify hierarchical-only execution
```

## 8. Corriger `TASK-MEMORY-EVIDENCE-OPERATIONS.md`

- [x] Remplacer le statut de « procédure cible » par une description de l'architecture active.
- [x] Indiquer que Temporal est actif et obligatoire.
- [x] Indiquer que Temporal gouverne ordre, timers, retries, annulations et signaux.
- [x] Indiquer que PostgreSQL est la projection métier active et reconstruisible.
- [x] Indiquer qu'Evidence MCP est actif et constitue l'autorité des contenus et digests.
- [x] Supprimer l'affirmation selon laquelle la projection PostgreSQL n'est pas câblée.
- [x] Supprimer l'affirmation selon laquelle l'adaptateur mémoire est actif.
- [x] Présenter `PostgresTaskMemory` comme l'implémentation active du port interne `TaskMemory`.
- [x] Présenter `InMemoryTaskMemory` comme un composant de test ou de migration historique uniquement.
- [x] Documenter l'outbox d'admission Temporal.
- [x] Documenter la détection des projections `potentiallyStale`.
- [x] Actualiser les procédures de sauvegarde, restauration et reconstruction.
- [x] Retirer les formulations futures pour les composants déjà livrés.
- [x] Vérifier tous les liens et noms de classes cités.
- [x] Rechercher les mêmes affirmations obsolètes dans les autres documents actifs.
- [x] Corriger ou archiver les documents actifs qui contredisent le nouvel état courant.

### Option distincte si le port `TaskMemory` doit réellement disparaître

- [ ] Ouvrir un plan de refactoring séparé.
- [ ] Renommer le port en `TaskProjectionStore` ou injecter directement un port de projection équivalent.
- [ ] Migrer `TaskService`.
- [ ] Migrer `TemporalAdmissionReconciler`.
- [ ] Migrer `PipelineExecutionActivitiesImpl`.
- [ ] Renommer ou remplacer `PostgresTaskMemory`.
- [ ] Remplacer `InMemoryTaskMemory` par des fakes situés dans les sources de test.
- [ ] Adapter contrôleurs, tests et documentation.
- [ ] Supprimer `TaskMemory` seulement lorsque `rg '\bTaskMemory\b' apps/orchestrator/src/main` ne retourne plus
  aucun consommateur.

### Critères de sortie du lot 8

- [x] Le document ne présente plus Temporal comme désactivé.
- [x] Le document ne présente plus l'adaptateur mémoire comme actif.
- [x] Le document distingue clairement autorité Temporal, projection PostgreSQL et Evidence MCP.
- [x] Toute mention conservée de `TaskMemory` correspond à son utilisation réelle dans le code.

### Commit proposé

```text
docs(operations): align task projection guide with active runtime
```

## 9. Préparer la livraison

- [ ] Produire un nouveau build immuable de l'orchestrateur.
- [ ] Utiliser un nouveau Build ID Temporal pour la V2.
- [ ] Vérifier que le nouveau worker poll toutes les task queues attendues.
- [ ] Vérifier la politique de versioning et de pinning.
- [ ] Conserver le worker V1 pour les seuls historiques qui l'exigent.
- [ ] Préparer la commande de fermeture des admissions.
- [ ] Préparer la procédure de retour au build précédent.
- [ ] Définir une fenêtre de surveillance couvrant au minimum deux cycles complets de réconciliation.
- [ ] Informer l'exploitation que les anciennes valeurs de mode ne doivent plus être utilisées pour les nouvelles
  tâches.

## 10. Livraison A — activer le parcours hiérarchique

- [ ] Fermer les admissions.
- [ ] Sauvegarder les autorités nécessaires au rollback.
- [ ] Déployer le worker V2 sans supprimer les volumes Temporal ou PostgreSQL.
- [ ] Vérifier l'enregistrement de son nouveau Build ID.
- [ ] Rendre la V2 courante pour les nouvelles admissions.
- [ ] Rouvrir les admissions.
- [ ] Exécuter les scénarios de smoke hiérarchiques.
- [ ] Vérifier les erreurs de workflow et de replay.
- [ ] Vérifier les doublons A2A, Evidence et SCM.
- [ ] Vérifier latence, coût, backlog, saturation et décisions humaines.
- [ ] Observer au moins deux fenêtres sans anomalie avant de déclarer l'activation stable.

### Critères de sortie de la livraison A

- [ ] Toutes les nouvelles tâches utilisent exclusivement le workflow hiérarchique V2.
- [ ] Les workflows V1 restent servis par un worker compatible.
- [ ] Aucun effet externe n'est dupliqué.
- [ ] La projection PostgreSQL converge avec les historiques Temporal.

## 11. Livraison B — supprimer définitivement les reliquats

- [ ] Vérifier qu'aucun workflow V1 ouvert ne subsiste.
- [ ] Vérifier que la durée de rétention convenue est écoulée ou que tous les historiques nécessaires sont archivés.
- [ ] Retirer l'ancien Build ID du routage.
- [ ] Arrêter le worker V1.
- [ ] Supprimer les anciens contrats et classes V1 devenus inutiles.
- [ ] Supprimer les dernières valeurs sérialisées des anciens modes dans les chemins actifs.
- [ ] Supprimer les politiques, scripts, fixtures et runbooks obsolètes.
- [ ] Conserver uniquement les preuves historiques nécessaires dans `docs/archive/`.
- [ ] Régénérer les manifests et sommes de contrôle documentaires.
- [ ] Relancer toutes les validations globales.
- [ ] Observer une nouvelle fenêtre de stabilité après retrait.

### Critères de sortie de la livraison B

- [ ] Aucun worker actif ne supporte un ancien mode métier.
- [ ] Aucun paramètre public ou interne ne permet de sélectionner un ancien mode.
- [ ] Les recherches ciblées ne trouvent les anciens termes que dans les archives ou preuves historiques autorisées.
- [ ] La suppression n'a introduit aucune non-déterminisme Temporal.

## 12. Documentation et traçabilité finales

- [x] Mettre à jour `docs/overview/current-state.md`.
- [x] Mettre à jour `docs/delivery/roadmap/README.md`.
- [x] Marquer `ADR-MAH-005` et `ADR-MAH-008` comme remplacées par la décision hiérarchique définitive.
- [x] Retirer des documents actifs toute affirmation présentant `PIPELINE_BASELINE` comme un chemin standard du
  mode multi-agent hiérarchique ou comme un fallback de `HIERARCHICAL_ACTIVE`. _(Les occurrences conservées sont
  limitées aux ADR remplacées, preuves historiques, archives et au présent plan de retrait.)_
- [x] Décrire explicitement `SHORT_CODE_PATH` et `HIERARCHICAL_PATH` comme les deux chemins exécutables de la
  nouvelle architecture, avec `HUMAN_TRIAGE` comme décision de sécurité. _(`current-state.md` décrit les deux
  assemblages V2 natifs et leurs contrôles déterministes.)_
- [ ] Mettre à jour les runbooks de confinement et rollback.
- [ ] Référencer les tests de replay et les scénarios bout en bout.
- [ ] Enregistrer les Build IDs V1 et V2, dates de promotion et date de drainage.
- [ ] Enregistrer les commandes de validation et leurs résultats.
- [ ] Ne versionner aucun secret, jeton ou payload inutile.
- [ ] Cocher la tâche correspondante dans `docs/delivery/roadmap/NOTES.md` après clôture complète.

## Critères d'acceptation finaux

- [ ] Toutes les nouvelles tâches utilisent exclusivement le parcours hiérarchique.
- [ ] `PIPELINE`, `HIERARCHICAL_SHADOW`, `HIERARCHICAL_CANARY` et `PIPELINE_BASELINE` ne sont plus acceptés par les
  contrats actifs.
- [ ] Aucun fallback vers un ancien parcours ne subsiste.
- [ ] Le kill switch confine la fabrique sans dépendre d'un ancien mode.
- [ ] Tous les historiques conservés sont rejouables par un worker compatible.
- [ ] Les anciens workers sont drainés avant leur suppression.
- [ ] Les tests unitaires, d'intégration, Temporal, A2A et bout en bout réussissent.
- [ ] Aucun effet A2A, Evidence ou SCM n'est dupliqué.
- [ ] `TASK-MEMORY-EVIDENCE-OPERATIONS.md` reflète l'architecture réellement active.
- [ ] Les documents actifs ne contredisent plus l'état du code et du déploiement.

## Définition de terminé

- [ ] L'ADR hiérarchique définitive est approuvée.
- [ ] La V2 hiérarchique est déployée et stable.
- [ ] La V1 est drainée puis retirée.
- [ ] Tous les reliquats des anciens modes sont supprimés des chemins actifs.
- [ ] La documentation Task Memory, Temporal, PostgreSQL et Evidence est à jour.
- [ ] Les preuves de tests, replay, déploiement, rollback et surveillance sont archivées.
- [ ] Le plan est relu, fusionné et clôturé.
