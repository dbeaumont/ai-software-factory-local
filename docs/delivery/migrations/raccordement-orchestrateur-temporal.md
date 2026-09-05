# Plan de migration — raccordement de l'orchestrateur à Temporal

## 1. Objectif

Raccorder réellement le parcours public `POST /api/tasks` à Temporal afin que l'exécution, les reprises, les
signaux humains et la chronologie des tickets survivent aux redémarrages de l'orchestrateur.

La migration doit préserver :

- le pipeline déterministe actuel comme comportement fonctionnel de référence ;
- les gates de tests, qualité, sécurité, revue et approbation humaine ;
- l'idempotence des opérations MCP et SCM ;
- le développement local sur macOS avec Docker Compose ;
- un retour arrière explicite pour les nouvelles admissions, sans réinterpréter les workflows déjà démarrés.

```text
API / UI
   |
   v
Task application service
   |
   +--> projection PostgreSQL reconstruisible
   |
   v
Temporal client --> SoftwareFactoryExecutionWorkflow
                       |
                       +--> activités contexte / LLM / sandbox / assurance / preuves / SCM
                       +--> child workflows de délégation et de revue indépendante
                       +--> signaux approbation / décision / annulation
```

## 2. État initial constaté

- Les services `temporal-db`, `temporal`, `temporal-namespace` et `temporal-ui` existent dans Docker Compose.
- Le namespace local `ai-factory-local` est créé avec une rétention de sept jours.
- Les contrats et implémentations `SoftwareFactoryWorkflow`, `DelegationWorkflow`, `PatchIntegrationWorkflow` et
  `IndependentReviewWorkflow` existent et sont couverts par des tests avec le serveur Temporal embarqué.
- Les activités `DurableExecutionActivities` et `PatchIntegrationActivities` existent, mais aucun worker de
  production ne les enregistre.
- Aucun bean de production ne construit `WorkflowServiceStubs`, `WorkflowClient`, `WorkerFactory` ou `Worker`.
- `DeterministicWorkflowCoordinator` est l'unique implémentation Spring active de `WorkflowCoordinator`.
- `AI_FACTORY_TEMPORAL_ENABLED=true` est actuellement transmis à l'orchestrateur local, mais ce flag ne modifie
  pas encore le chemin d'exécution des tickets.
- `TaskMemory` utilise `InMemoryTaskMemory` : un redémarrage efface la vue API et le lien avec les tâches actives.
- Les commandes d'approbation, de décision, d'annulation et de fallback modifient directement `TaskState` au lieu
  d'émettre des signaux Temporal.

## 3. Résultat attendu

- [ ] Un ticket accepté démarre un workflow Temporal avec des identifiants déterministes et vérifiables.
- [ ] Temporal est l'autorité de la chronologie, des retries, des timers et des signaux d'une exécution active.
- [ ] PostgreSQL fournit une projection de lecture durable à l'API et à l'interface, sans lire directement les
  tables internes de Temporal.
- [ ] Le pipeline fonctionnel actuel est découpé en activités idempotentes et reprend à la dernière étape validée.
- [ ] Les effets externes passent exclusivement par les MCP autorisés et utilisent une clé d'idempotence stable.
- [ ] Une approbation, une décision, une annulation ou un fallback est transmis au workflow par un signal validé.
- [ ] Un redémarrage de l'orchestrateur ou d'un worker ne perd ni tâche, ni preuve, ni état d'effet.
- [ ] Docker Compose permet de développer, tester, observer et rejouer Temporal sur macOS.
- [ ] Le mode actif peut être désactivé pour les nouvelles admissions sans abandonner les workflows existants.
- [ ] Aucun fallback automatique vers l'exécuteur local n'est effectué lorsque Temporal est indisponible.

## 4. Décisions d'architecture à figer

- [ ] **TEMP-001 — Séparer moteur et mode d'exécution.** Ajouter un axe `workflowEngine` (`LOCAL`, `TEMPORAL`)
  distinct de `executionMode` (`PIPELINE`, `HIERARCHICAL_SHADOW`, `HIERARCHICAL_CANARY`,
  `HIERARCHICAL_ACTIVE`).
- [ ] **TEMP-002 — Définir l'autorité des données.** Temporal porte la chronologie et l'état de coordination ;
  PostgreSQL porte une projection reconstruisible ; Evidence MCP porte les artefacts et leurs digests ; Gitea
  reste l'autorité des effets SCM.
- [ ] **TEMP-003 — Interdire l'accès applicatif à la base Temporal.** Les lectures passent par le SDK/API Temporal
  et jamais par les tables PostgreSQL internes de Temporal.
- [ ] **TEMP-004 — Choisir un workflow racine de production versionné.** Introduire
  `SoftwareFactoryExecutionWorkflowV1` ou faire évoluer le contrat existant avec une stratégie explicite
  `Workflow.getVersion`; documenter le choix dans une ADR.
- [ ] **TEMP-005 — Définir le workflow ID.** Utiliser un identifiant stable tel que
  `ai-factory/{taskId}/{attemptId}`, avec une politique de réutilisation qui refuse les doublons non terminés.
- [ ] **TEMP-006 — Définir les Run IDs et tentatives.** Ne jamais utiliser un Run ID aléatoire comme clé métier ;
  conserver `taskId`, `attemptId`, `sourceCommit` et `repositoryId` dans chaque entrée, activité et preuve.
- [ ] **TEMP-007 — Définir le fail-closed.** Si Temporal est sélectionné et indisponible, refuser ou mettre en
  attente l'admission ; ne pas lancer implicitement `DeterministicWorkflowCoordinator`.
- [ ] **TEMP-008 — Définir le rollback.** Le rollback change le moteur des nouvelles admissions uniquement ; les
  workflows Temporal en cours restent servis par des workers compatibles jusqu'à drainage.

### Critères de sortie du cadrage

- [ ] L'ADR précise autorités, identifiants, versionnement, retry, timeout, annulation et rollback.
- [ ] Le moteur de workflow et le mode multi-agent ne sont plus confondus dans les modèles, métriques ou écrans.
- [ ] Chaque opération à effet possède un propriétaire, une clé d'idempotence et une procédure de réconciliation.

## 5. Lot 0 — remettre la configuration en état fail-closed

- [ ] **TEMP-010 — Remplacer le booléen ambigu.** Introduire `AI_FACTORY_WORKFLOW_ENGINE=local|temporal` et garder
  temporairement `AI_FACTORY_TEMPORAL_ENABLED` comme alias déprécié avec avertissement explicite.
- [ ] **TEMP-011 — Refuser une fausse activation.** Tant que le client et les workers ne sont pas enregistrés,
  faire échouer le démarrage lorsque le moteur demandé vaut `temporal`.
- [ ] **TEMP-012 — Conserver `.env.example` en mode sûr.** Laisser `local` comme valeur par défaut et documenter
  l'opt-in local après qualification.
- [ ] **TEMP-013 — Valider la configuration de connexion.** Vérifier cible, namespace, rétention, task queues,
  TLS, certificat client, nom de serveur et fichier de clé API sans journaliser les secrets.
- [ ] **TEMP-014 — Vérifier la compatibilité.** Tester et documenter la matrice entre le serveur Temporal 1.31.2,
  le SDK Java 1.38.0 et les fonctionnalités utilisées, notamment Worker Versioning.
- [ ] **TEMP-015 — Corriger l'accès à Temporal UI.** Vérifier que `127.0.0.1:8233` est réellement publié sur
  macOS malgré le réseau Compose interne, sans exposer le frontend gRPC Temporal sur l'hôte.
- [ ] **TEMP-016 — Ajouter des cibles opérateur.** Fournir `make temporal-status`, `make temporal-logs`,
  `make temporal-ui`, `make temporal-enable-local` et `make temporal-disable-local` sans afficher de secret.

### Critères de sortie du lot 0

- [ ] `make config` refuse toute configuration incohérente.
- [ ] Le statut distingue « infrastructure démarrée » et « moteur de tickets actif ».
- [ ] Temporal UI est joignable uniquement depuis l'hôte local à l'adresse documentée.

## 6. Lot 1 — extraire le pipeline en étapes reprenables

- [ ] **TEMP-020 — Isoler les étapes métier.** Extraire de `DeterministicWorkflowCoordinator` des services sans
  ordonnanceur interne pour clonage, contexte, planification, génération/réparation, patch, tests, qualité,
  sécurité, revue et livraison.
- [ ] **TEMP-021 — Définir des commandes/résultats immuables.** Chaque étape reçoit un payload versionné borné à
  `taskId`, `attemptId`, `sourceCommit`, digests d'entrées et identité d'exécution.
- [ ] **TEMP-022 — Rendre les sorties persistables.** Éviter de transporter de gros logs ou documents dans
  l'historique Temporal ; stocker les contenus dans Evidence MCP et retourner URI, digest, taille et verdict.
- [ ] **TEMP-023 — Retirer les écritures implicites dans `TaskState`.** Faire retourner aux étapes des événements
  métier explicites appliqués ensuite à la projection.
- [ ] **TEMP-024 — Formaliser l'idempotence.** Dériver les clés des effets depuis workflow ID, étape, séquence,
  source commit et digest d'entrée ; rejeter une réutilisation avec un payload différent.
- [ ] **TEMP-025 — Classer les erreurs.** Distinguer erreurs métier non retryables, erreurs de contrat, saturation,
  timeout, dépendance indisponible et issue d'effet inconnue.
- [ ] **TEMP-026 — Définir les politiques temporelles.** Fixer pour chaque activité start-to-close,
  schedule-to-close, heartbeat timeout, nombre de tentatives et backoff.
- [ ] **TEMP-027 — Conserver l'exécuteur local.** Recomposer le pipeline historique avec les nouveaux services afin
  de disposer d'une baseline et d'un rollback fonctionnel pendant la migration.
- [ ] **TEMP-028 — Supprimer l'ordonnancement caché.** Remplacer le pool interne de
  `DeterministicWorkflowCoordinator` par un port d'exécution explicite contrôlé par le moteur sélectionné.

### Critères de sortie du lot 1

- [ ] Les tests historiques du pipeline passent sans changement de verdict.
- [ ] Chaque étape peut être rejouée avec la même entrée sans doubler un effet.
- [ ] Aucun appel LLM, MCP, filesystem, réseau ou horloge non déterministe ne se trouve dans le code workflow.

## 7. Lot 2 — construire le client Temporal et les workers de production

- [ ] **TEMP-030 — Créer les beans conditionnels.** Construire `WorkflowServiceStubs`, `WorkflowClient`,
  `WorkerFactory` et les workers uniquement lorsque `workflowEngine=temporal`.
- [ ] **TEMP-031 — Implémenter la sécurité du client.** Charger TLS/mTLS et clé API depuis des fichiers montés,
  vérifier les permissions et ne jamais injecter les secrets dans les inputs de workflow.
- [ ] **TEMP-032 — Enregistrer le workflow racine.** Enregistrer l'implémentation de production sur
  `ai-factory-workflows` avec un Build ID/version de déploiement explicite.
- [ ] **TEMP-033 — Enregistrer les child workflows.** Enregistrer délégations, intégration de patch et revue
  indépendante sur les task queues décidées par l'architecture.
- [ ] **TEMP-034 — Enregistrer les activités.** Câbler les adaptateurs contexte, LLM, sandbox, assurance,
  evidence et SCM sur leurs files respectives.
- [ ] **TEMP-035 — Configurer la capacité.** Fixer concurrence des pollers et activités, débit, cache workflows,
  graceful shutdown et durée maximale de drainage.
- [ ] **TEMP-036 — Ajouter les interceptors.** Propager `traceparent`/baggage validés, identité d'exécution et
  métriques sans produire de spans lors d'un replay.
- [ ] **TEMP-037 — Exposer la readiness.** Considérer l'orchestrateur prêt en mode Temporal seulement si le
  namespace est accessible et si les workers requis sont démarrés.
- [ ] **TEMP-038 — Gérer le cycle de vie.** Démarrer la factory après l'enregistrement complet et l'arrêter avec
  drainage borné avant fermeture du client.

### Critères de sortie du lot 2

- [ ] Les task queues attendues possèdent chacune au moins un poller visible dans Temporal.
- [ ] Aucun worker n'est créé lorsque le moteur vaut `local`.
- [ ] Une erreur TLS, namespace ou task queue empêche clairement la readiness en mode Temporal.

## 8. Lot 3 — implémenter le workflow racine de production

- [ ] **TEMP-040 — Déplacer la résolution de source dans une activité.** Résoudre et attester la branche avant tout
  travail ; figer `sourceCommit` pour toute la tentative.
- [ ] **TEMP-041 — Orchestrer le pipeline étape par étape.** Appeler les activités extraites dans l'ordre et
  enregistrer uniquement des références de preuves compactes dans l'historique.
- [ ] **TEMP-042 — Intégrer la réparation de patch.** Modéliser les tentatives comme une boucle workflow bornée,
  déterministe et observable, sans retry d'activité aveugle sur une erreur de patch.
- [ ] **TEMP-043 — Intégrer les gates.** Une gate refusée termine la tentative avec un état métier explicite et
  conserve toutes les preuves déjà produites.
- [ ] **TEMP-044 — Intégrer le DAG multi-agent.** N'activer les child workflows hiérarchiques que pour les modes
  autorisés ; conserver `PIPELINE` comme comportement initial du moteur Temporal.
- [ ] **TEMP-045 — Intégrer la revue indépendante.** Lier la revue aux digests du plan, du patch, des tests, de la
  qualité, de la sécurité et du commit source.
- [ ] **TEMP-046 — Attendre l'approbation sans thread bloqué.** Utiliser un signal Temporal et `Workflow.await`,
  avec manifeste immuable et vérification de l'approbateur côté activité/hôte.
- [ ] **TEMP-047 — Encadrer l'effet SCM.** Livrer par une activité idempotente, puis réconcilier Gitea avant tout
  retry lorsque l'issue réseau est inconnue.
- [ ] **TEMP-048 — Gérer annulation et compensation.** Annuler les activités cancellables, préserver les preuves
  et ne jamais tenter d'annuler un effet SCM déjà confirmé.
- [ ] **TEMP-049 — Borner l'historique.** Utiliser `continue-as-new` avant les seuils d'événements ou de taille en
  transportant uniquement l'état minimal vérifié.
- [ ] **TEMP-050 — Versionner le déterminisme.** Couvrir toute évolution incompatible par Worker Versioning,
  nouveau type de workflow ou `Workflow.getVersion`.

### Critères de sortie du lot 3

- [ ] Un ticket complet atteint `WAITING_APPROVAL`, reçoit un signal, puis crée exactement une PR brouillon.
- [ ] La chronologie Temporal permet d'expliquer chaque transition exposée par l'API.
- [ ] Un replay des historiques de référence passe avec zéro erreur de non-déterminisme.

## 9. Lot 4 — commandes applicatives et signaux

- [ ] **TEMP-060 — Créer un `TemporalWorkflowCoordinator`.** Implémenter `start` et `resumeAfterApproval` avec des
  stubs typés et des options de démarrage déterministes.
- [ ] **TEMP-061 — Router par configuration hôte.** Sélectionner le coordinateur local ou Temporal au démarrage ;
  un ticket ou une sortie de modèle ne peut jamais augmenter le mode.
- [ ] **TEMP-062 — Signaler l'approbation.** Transformer `approve`/`approve-manifest` en signal lié à task,
  tentative, manifeste, digest, acteur et horodatage.
- [ ] **TEMP-063 — Signaler les décisions humaines.** Vérifier domaine, rôle, options et object digest avant
  émission du signal.
- [ ] **TEMP-064 — Signaler l'annulation.** Rendre l'opération idempotente et retourner l'état projeté sans
  supposer que le workflow est déjà terminé.
- [ ] **TEMP-065 — Implémenter retry et fallback opérateur.** Créer une nouvelle tentative liée à l'ancienne ; ne
  jamais changer le moteur ou répéter un effet au milieu du même historique.
- [ ] **TEMP-066 — Gérer les conflits de commande.** Définir les réponses pour workflow absent, terminé,
  approbation expirée, digest périmé, signal dupliqué et projection en retard.
- [ ] **TEMP-067 — Auditer les commandes.** Journaliser l'intention et le résultat avec corrélation, sans contenu
  sensible ni secret.

### Critères de sortie du lot 4

- [ ] Toutes les routes de commande existantes fonctionnent sur les deux moteurs.
- [ ] Un signal dupliqué ne change pas deux fois l'état et ne déclenche pas deux effets.
- [ ] L'API distingue acceptation de commande, application au workflow et mise à jour de projection.

## 10. Lot 5 — projection durable et reconstruction

- [ ] **TEMP-070 — Ajouter une base applicative dédiée.** Déployer `orchestrator-db` dans Compose ; ne pas
  réutiliser `temporal-db` ni ses identifiants.
- [ ] **TEMP-071 — Versionner le schéma.** Créer les migrations pour tâches, tentatives, runs, transitions,
  délégations, artefacts, contradictions, décisions, actions humaines et effets en attente.
- [ ] **TEMP-072 — Implémenter `PostgresTaskMemory`.** Fournir lectures et écritures transactionnelles avec
  verrouillage optimiste et contraintes d'unicité.
- [ ] **TEMP-073 — Persister l'admission avant le démarrage.** Utiliser une outbox ou une procédure de
  réconciliation afin d'éviter l'état « ligne créée, workflow absent » et l'inverse.
- [ ] **TEMP-074 — Projeter les événements.** Mettre à jour le read model depuis des activités de projection
  idempotentes ou depuis l'historique Temporal avec un curseur durable.
- [ ] **TEMP-075 — Détecter le retard.** Exposer l'âge et la position de projection et signaler une vue
  potentiellement obsolète sans inventer un succès.
- [ ] **TEMP-076 — Reconstruire une tâche.** Rejouer l'historique Temporal et vérifier les digests Evidence avant
  remplacement atomique de la projection.
- [ ] **TEMP-077 — Reconstruire toutes les projections.** Ajouter une commande opérateur bornée, observable et
  réentrante avec mode dry-run.
- [ ] **TEMP-078 — Persister la séquence des tickets.** Remplacer le compteur JVM `AF-xxxx` par une séquence
  durable sans collision après redémarrage.
- [ ] **TEMP-079 — Migrer les tâches locales utiles.** Utiliser `LegacyTaskMigrator`, marquer la provenance et ne
  pas fabriquer d'historique Temporal pour une ancienne exécution locale.

### Critères de sortie du lot 5

- [ ] Un redémarrage de l'orchestrateur conserve la liste et le détail des tâches.
- [ ] Une projection supprimée peut être reconstruite depuis Temporal et Evidence MCP.
- [ ] Un écart de digest arrête la reconstruction et produit une alerte de sécurité.

## 11. Lot 6 — observabilité, exploitation et sécurité

- [ ] **TEMP-080 — Corréler les signaux.** Ajouter workflow ID, run ID, task ID, attempt ID, task queue, workflow
  type et activity type aux traces, métriques et logs selon les règles de cardinalité.
- [ ] **TEMP-081 — Mesurer les files.** Collecter backlog, schedule-to-start, retries, timeouts, pollers,
  saturation et workflows bloqués en attente humaine.
- [ ] **TEMP-082 — Compléter le dashboard SigNoz Temporal.** Ajouter santé client/worker, files par périmètre,
  erreurs d'activités et liens profonds vers Temporal UI.
- [ ] **TEMP-083 — Ajouter les alertes.** Couvrir absence de poller, backlog durable, erreur non déterministe,
  projection en retard, activité bloquée et échec de continue-as-new.
- [ ] **TEMP-084 — Protéger les données.** Vérifier qu'aucun secret, patch complet, log volumineux ou donnée
  confidentielle n'entre dans les inputs, search attributes ou memo Temporal.
- [ ] **TEMP-085 — Définir les Search Attributes.** N'enregistrer que les dimensions nécessaires, bornées et non
  sensibles ; versionner leur création locale.
- [ ] **TEMP-086 — Sauvegarder et restaurer.** Tester sauvegarde cohérente de `temporal-db`, `orchestrator-db` et
  Evidence MCP, puis restauration dans l'ordre documenté.
- [ ] **TEMP-087 — Tester la rétention.** Vérifier expiration des historiques, conservation légale des preuves et
  comportement de la projection lorsque l'historique n'est plus disponible.
- [ ] **TEMP-088 — Mettre à jour les runbooks.** Compléter indisponibilité Temporal, worker défaillant, saturation,
  rollback, projection incohérente et effet à issue inconnue.

### Critères de sortie du lot 6

- [ ] Une tâche est navigable de l'interface vers SigNoz, Temporal UI, Evidence MCP et Gitea.
- [ ] Les alertes sont testées par injection de panne et reviennent automatiquement à l'état normal.
- [ ] La restauration conserve l'idempotence et n'entraîne aucun rejeu SCM non autorisé.

## 12. Lot 7 — stratégie de tests

### 12.1 Tests unitaires et d'architecture

- [ ] Tester validation des options client, namespace, queues, TLS et secrets par fichier.
- [ ] Tester le routage `LOCAL`/`TEMPORAL` et le refus de fallback implicite.
- [ ] Tester identifiants, clés d'idempotence, classification des erreurs et politiques de retry.
- [ ] Interdire par test d'architecture réseau, filesystem, horloge système, thread, random et client MCP dans les
  implémentations de workflow.
- [ ] Tester la taille maximale des inputs/résultats et l'externalisation des contenus vers Evidence MCP.
- [ ] Tester les transitions et commandes invalides sur la projection.

### 12.2 Tests Temporal embarqués

- [ ] Tester le parcours nominal jusqu'à l'approbation et la livraison.
- [ ] Tester rejet de chaque gate et préservation des preuves partielles.
- [ ] Tester retries déterministes, heartbeat, timeout et activité non retryable.
- [ ] Tester signaux reçus avant et pendant `Workflow.await`.
- [ ] Tester annulation en clonage, LLM, sandbox, attente humaine et livraison.
- [ ] Tester `continue-as-new` et propagation de l'état minimal.
- [ ] Tester child workflows parallèles, échec en cascade et revue indépendante.
- [ ] Tester l'unicité de l'effet SCM après perte d'accusé de réception.

### 12.3 Tests de replay et compatibilité

- [ ] Versionner des historiques JSON de référence pour succès, échec, attente, annulation et continue-as-new.
- [ ] Exécuter automatiquement le replay contre chaque nouvelle version du worker.
- [ ] Tester un ancien worker et un nouveau worker simultanément avec Build IDs compatibles.
- [ ] Refuser la livraison d'une image worker lorsque le replay échoue.

### 12.4 Tests d'intégration Docker Compose sur macOS

- [ ] Démarrer une stack neuve et vérifier namespace, pollers, UI et readiness.
- [ ] Soumettre un ticket réel et vérifier son apparition dans Temporal UI.
- [ ] Tuer puis recréer l'orchestrateur pendant chaque phase critique.
- [ ] Tuer un worker pendant une activité avec heartbeat et vérifier la reprise.
- [ ] Redémarrer Temporal puis PostgreSQL en préservant les volumes.
- [ ] Simuler indisponibilité MCP, LiteLLM, Gitea, SonarQube, Artifactory et Collector.
- [ ] Vérifier le pipeline complet : patch, tests, Sonar, Trivy, revue, approbation et une seule PR.
- [ ] Vérifier que `docker compose down` puis `up` conserve tâches et historiques.

### 12.5 Tests de charge et de durée

- [ ] Soumettre des tickets concurrents au-delà de la capacité worker et mesurer le backpressure.
- [ ] Tester les limites globales et par task queue.
- [ ] Tester une attente humaine supérieure à un redémarrage et à une rotation de worker.
- [ ] Tester rétention, purge et reconstruction sur un jeu représentatif.

## 13. Lot 8 — bascule progressive

- [ ] **TEMP-100 — Capturer la baseline locale.** Versionner résultats, coûts, durées, digests et états du pipeline
  local sur un corpus de tickets fixe.
- [ ] **TEMP-101 — Activer le shadow sans effet.** Faire produire au workflow Temporal une chronologie/projection
  à partir d'entrées et preuves capturées, sans doubler LLM, sandbox ou SCM.
- [ ] **TEMP-102 — Comparer automatiquement.** Comparer transitions, verdicts, digests, erreurs, coût et durée ;
  classer toute divergence.
- [ ] **TEMP-103 — Passer en canary.** Autoriser Temporal pour une allow-list de dépôts et un pourcentage stable,
  avec kill switch et budget dédiés.
- [ ] **TEMP-104 — Qualifier les pannes.** Exécuter redémarrages, latence, partition réseau, saturation, doublons de
  signaux et issues d'effets inconnues.
- [ ] **TEMP-105 — Obtenir la gate.** Exiger validation produit, architecture, sécurité et exploitation avant le
  passage actif.
- [ ] **TEMP-106 — Activer localement.** Définir `AI_FACTORY_WORKFLOW_ENGINE=temporal` dans `.env` uniquement après
  succès de la campagne macOS.
- [ ] **TEMP-107 — Étendre progressivement.** Augmenter le canary par paliers observés avant de déclarer Temporal
  moteur par défaut.
- [ ] **TEMP-108 — Drainer l'ancien chemin.** Conserver le moteur local jusqu'à expiration de la fenêtre de retour
  arrière, puis retirer son ordonnancement asynchrone.

### Critères de promotion

- [ ] Zéro divergence de verdict ou d'effet inexpliquée sur le corpus apparié.
- [ ] Zéro PR, commit ou preuve dupliquée pendant les tests de panne.
- [ ] Tous les historiques de référence sont rejouables par l'image candidate.
- [ ] Les SLO de disponibilité, reprise, latence, coût et backlog sont respectés sur deux fenêtres stables.
- [ ] Sauvegarde, restauration et rollback ont été exécutés, pas seulement documentés.

## 14. Procédure de rollback

- [ ] Fermer les nouvelles admissions Temporal ou sélectionner `workflowEngine=local` pour les nouvelles tâches.
- [ ] Identifier tous les workflows Temporal ouverts, leur Build ID, leur phase et leurs effets en attente.
- [ ] Conserver les workers compatibles nécessaires au drainage des workflows existants.
- [ ] Ne jamais relancer localement une tentative Temporal avec le même `attemptId`.
- [ ] Créer une nouvelle tentative liée lorsque le pipeline local doit reprendre une demande.
- [ ] Réconcilier toute activité SCM à issue inconnue avec Gitea avant une nouvelle commande.
- [ ] Préserver `temporal-db-data`, `orchestrator-db-data`, Evidence MCP et workspaces pendant l'incident.
- [ ] Vérifier la cohérence de la projection après stabilisation et reconstruire uniquement depuis les autorités.
- [ ] Documenter cause, périmètre, tâches affectées, décision de reprise et preuves du rollback.

## 15. Ordre d'exécution et dépendances

```text
TEMP-001..008  décisions d'architecture
      |
TEMP-010..016  configuration fail-closed
      |
TEMP-020..028  extraction du pipeline
      |
      +-------------------+
      v                   v
TEMP-030..038 workers   TEMP-070..079 projection durable
      |                   |
      +---------+---------+
                v
TEMP-040..050 workflow racine
                |
TEMP-060..067 commandes et signaux
                |
TEMP-080..088 exploitation
                |
TEMP-100..108 shadow, canary, actif
```

- [ ] Traiter chaque ticket dans un commit dédié de la forme `feat(temporal): TEMP-xxx ...`.
- [ ] Ne cocher un ticket qu'après tests associés, mise à jour documentaire et commit réussi.
- [ ] Inscrire le hash du commit et la preuve de validation à côté de chaque case cochée.
- [ ] Ne pas mélanger une modification de workflow déterministe avec une mise à jour de dépendance ou
  d'infrastructure non liée.
- [ ] Créer un historique de replay avant toute modification incompatible du code workflow.

## 16. Matrice de preuves obligatoire

| Preuve | Local | Shadow | Canary | Actif |
|---|---:|---:|---:|---:|
| Tests unitaires et architecture | [ ] | [ ] | [ ] | [ ] |
| Tests Temporal embarqués | [ ] | [ ] | [ ] | [ ] |
| Replay des historiques versionnés | [ ] | [ ] | [ ] | [ ] |
| Parcours Docker Compose macOS | [ ] | [ ] | [ ] | [ ] |
| Reprise après arrêt orchestrateur | [ ] | [ ] | [ ] | [ ] |
| Reprise après arrêt worker | [ ] | [ ] | [ ] | [ ] |
| Signaux humains idempotents | [ ] | [ ] | [ ] | [ ] |
| Effet SCM exactement une fois observable | [ ] | [ ] | [ ] | [ ] |
| Reconstruction PostgreSQL | [ ] | [ ] | [ ] | [ ] |
| Sauvegarde et restauration | [ ] | [ ] | [ ] | [ ] |
| Dashboards et alertes SigNoz | [ ] | [ ] | [ ] | [ ] |
| Rollback exécuté | [ ] | [ ] | [ ] | [ ] |

## 17. Définition de terminé

- [ ] `POST /api/tasks` démarre un workflow Temporal et retourne son identité durable.
- [ ] Toutes les commandes API sont traduites en signaux ou en nouvelles tentatives contrôlées.
- [ ] Une panne ou un redémarrage ne perd aucune tâche et ne duplique aucun effet.
- [ ] L'API et l'interface reposent sur une projection PostgreSQL reconstruisible.
- [ ] Les gros artefacts restent hors de l'historique Temporal et sont vérifiés par digest.
- [ ] Les workers sont versionnés, drainables et couverts par des tests de replay bloquants.
- [ ] Les files, retries, timeouts, pollers, projections et attentes humaines sont observables dans SigNoz.
- [ ] Temporal UI est disponible localement sans exposer le frontend gRPC au réseau hôte.
- [ ] Le parcours complet est qualifié sur macOS avec Docker Compose.
- [ ] Le rollback vers le moteur local a été exécuté sans perte ni double effet.
- [ ] La documentation d'état courant ne présente plus Temporal comme seulement disponible ou non câblé.

## 18. Hors périmètre de cette migration

- [ ] Ne pas fusionner automatiquement les Pull Requests créées.
- [ ] Ne pas utiliser la base interne Temporal comme base métier.
- [ ] Ne pas activer le mode hiérarchique simplement parce que le moteur Temporal est actif.
- [ ] Ne pas exposer Temporal gRPC publiquement pour faciliter le diagnostic local.
- [ ] Ne pas considérer le succès des tests embarqués comme une qualification de production.
- [ ] Ne pas supprimer le moteur local avant la fin de la fenêtre de stabilisation et le test du rollback.
