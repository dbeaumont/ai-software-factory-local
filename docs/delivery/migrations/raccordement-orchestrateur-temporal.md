# Plan de migration — raccordement de l'orchestrateur à Temporal

## 1. Objectif

Raccorder réellement le parcours public `POST /api/tasks` à Temporal afin que l'exécution, les reprises, les
signaux humains et la chronologie des tickets survivent aux redémarrages de l'orchestrateur.

La migration doit préserver :

- le pipeline déterministe actuel comme comportement fonctionnel de référence ;
- les gates de tests, qualité, sécurité, revue et approbation humaine ;
- l'idempotence des opérations MCP et SCM ;
- le développement local sur macOS avec Docker Compose ;
- une bascule franche vers Temporal, suivie d'un rollback de version Temporal possible sans réinterpréter les
  workflows déjà démarrés.

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
- Le sélecteur historique `AI_FACTORY_TEMPORAL_ENABLED` est retiré : la branche de migration doit raccorder
  Temporal comme dépendance obligatoire avant sa release de coupure.
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
- [ ] Une approbation, une décision ou une annulation est transmise au workflow par un signal validé ; le fallback
  vers le moteur local est supprimé.
- [ ] Un redémarrage de l'orchestrateur ou d'un worker ne perd ni tâche, ni preuve, ni état d'effet.
- [ ] Docker Compose permet de développer, tester, observer et rejouer Temporal sur macOS.
- [ ] À la date de coupure, toutes les nouvelles admissions utilisent Temporal, sans routage mixte, shadow ou
  canary.
- [ ] Lorsque Temporal est indisponible, les admissions sont suspendues ; aucun fallback vers l'exécuteur local
  n'est possible.

## 4. Décisions d'architecture à figer

- [x] **TEMP-001 — Séparer moteur et mode d'exécution.** Faire de Temporal l'unique moteur de production, tout en
  conservant `executionMode` comme caractéristique métier indépendante du workflow technique. _(Décision consignée
  dans `ADR-TEMP-001`.)_
- [x] **TEMP-002 — Définir l'autorité des données.** Temporal porte la chronologie et l'état de coordination ;
  PostgreSQL porte une projection reconstruisible ; Evidence MCP porte les artefacts et leurs digests ; Gitea
  reste l'autorité des effets SCM. _(Matrice normative ajoutée à `ADR-TEMP-001`.)_
- [x] **TEMP-003 — Interdire l'accès applicatif à la base Temporal.** Les lectures passent par le SDK/API Temporal
  et jamais par les tables PostgreSQL internes de Temporal. _(Frontière documentée et protégée par
  `TemporalDatabaseBoundaryTest`.)_
- [x] **TEMP-004 — Choisir un workflow racine de production versionné.** Introduire
  `SoftwareFactoryExecutionWorkflowV1` ou faire évoluer le contrat existant avec une stratégie explicite
  `Workflow.getVersion`; documenter le choix dans une ADR. _(Le nouveau type V1, ses règles d'évolution et de
  drainage sont fixés dans `ADR-TEMP-001`.)_
- [x] **TEMP-005 — Définir le workflow ID.** Utiliser un identifiant stable tel que
  `ai-factory/{taskId}/{attemptId}`, avec une politique de réutilisation qui refuse les doublons non terminés.
  _(Format implémenté dans `TemporalIds`, doublons refusés pour toute tentative déjà créée.)_
- [x] **TEMP-006 — Définir les Run IDs et tentatives.** Ne jamais utiliser un Run ID aléatoire comme clé métier ;
  conserver `taskId`, `attemptId`, `sourceCommit` et `repositoryId` dans chaque entrée, activité et preuve.
  _(Identités, origine, immutabilité et usages fixés dans `ADR-TEMP-001`.)_
- [x] **TEMP-007 — Définir le fail-closed.** Si Temporal est indisponible, refuser ou mettre en attente
  l'admission ; ne jamais lancer implicitement `DeterministicWorkflowCoordinator`. _(Politique de readiness,
  admission et reprise fixée dans `ADR-TEMP-001`.)_
- [x] **TEMP-008 — Définir le rollback.** Le rollback redéploie une version compatible des workers Temporal ; il ne
  réactive jamais le coordinateur local et les workflows en cours restent servis jusqu'à drainage. _(Procédure
  normative fixée dans `ADR-TEMP-001`.)_

### Critères de sortie du cadrage

- [x] L'ADR précise autorités, identifiants, versionnement, retry, timeout, annulation et rollback.
- [x] Temporal est présenté comme moteur unique sans être confondu avec le mode multi-agent dans les modèles,
  métriques ou écrans.
- [x] Chaque opération à effet possède un propriétaire, une clé d'idempotence et une procédure de réconciliation.

## 5. Lot 0 — remettre la configuration en état fail-closed

- [x] **TEMP-010 — Supprimer le sélecteur de moteur.** Déprécier puis retirer `AI_FACTORY_TEMPORAL_ENABLED` : une
  version post-bascule de l'orchestrateur exige Temporal et ne possède aucun mode `local`. _(Propriété supprimée
  du code, de Compose et des fichiers d'environnement.)_
- [x] **TEMP-011 — Refuser une fausse activation.** Faire échouer la readiness et suspendre les admissions tant que
  le client et tous les workers requis ne sont pas enregistrés. _(Gate réactive fail-closed avant persistance :
  workers et namespace obligatoires, probe gRPC borné hors thread Reactor, refus HTTP 503 sans fallback local.)_
- [x] **TEMP-012 — Aligner `.env.example`.** Fournir directement la configuration Temporal obligatoire et ne pas
  documenter de désactivation ou d'opt-in du moteur local. _(Configuration obligatoire et absence de fallback
  explicitées dans `.env.example` et `.env`.)_
- [x] **TEMP-013 — Valider la configuration de connexion.** Vérifier cible, namespace, rétention, task queues,
  TLS, certificat client, nom de serveur et fichier de clé API sans journaliser les secrets. _(Validation stricte
  des ports, queues, durées, combinaisons TLS et chemins absolus couverte par tests.)_
- [x] **TEMP-014 — Vérifier la compatibilité.** Tester et documenter la matrice entre le serveur Temporal 1.31.2,
  le SDK Java 1.38.0 et les fonctionnalités utilisées, notamment Worker Versioning. _(Matrice reproductible,
  versions Compose épinglées, handshake serveur et API Worker Versioning qualifiés.)_
- [x] **TEMP-015 — Corriger l'accès à Temporal UI.** Vérifier que `127.0.0.1:8233` est réellement publié sur
  macOS malgré le réseau Compose interne, sans exposer le frontend gRPC Temporal sur l'hôte. _(Bridge hôte dédié
  à la seule UI ; accès HTTP validé sur Docker Desktop, frontend gRPC toujours privé.)_
- [x] **TEMP-016 — Ajouter des cibles opérateur.** Fournir `make temporal-status`, `make temporal-logs` et
  `make temporal-ui` sans commande d'activation/désactivation du moteur et sans afficher de secret. _(Statut
  cluster/pollers séparé, logs ciblés et ouverture de l'UI loopback ajoutés au Makefile.)_

### Critères de sortie du lot 0

- [ ] `make config` refuse toute configuration incohérente.
- [ ] Le statut distingue « infrastructure démarrée » et « moteur de tickets actif ».
- [ ] Temporal UI est joignable uniquement depuis l'hôte local à l'adresse documentée.

## 6. Lot 1 — extraire le pipeline en étapes reprenables

- [x] **TEMP-020 — Isoler les étapes métier.** Extraire de `DeterministicWorkflowCoordinator` des services sans
  ordonnanceur interne pour clonage, contexte, planification, génération/réparation, patch, tests, qualité,
  sécurité, revue et livraison. _(`PipelineStepService` porte les effets réutilisables ; le coordinateur local ne
  conserve que l'ordre et son pool temporaire d'oracle.)_
- [x] **TEMP-021 — Définir des commandes/résultats immuables.** Chaque étape reçoit un payload versionné borné à
  `taskId`, `attemptId`, `sourceCommit`, digests d'entrées et identité d'exécution. _(`PipelineStepContracts`
  valide et fige identité, workflow ID, repository ID et digests ; chaque frontière d'étape l'exige.)_
- [x] **TEMP-022 — Rendre les sorties persistables.** Éviter de transporter de gros logs ou documents dans
  l'historique Temporal ; stocker les contenus dans Evidence MCP et retourner URI, digest, taille et verdict.
  _(Plans, patchs, tests, qualité, sécurité, SBOM et revues sont stockés par `EvidenceRepository` ; les résultats
  d'étape ne contiennent que des `ArtifactReference` compactes.)_
- [x] **TEMP-023 — Retirer les écritures implicites dans `TaskState`.** Faire retourner aux étapes des événements
  métier explicites appliqués ensuite à la projection. _(`PipelineProjectionEvent` modélise chaque mutation ; le
  service d'étapes émet les événements et seul l'applier met à jour `TaskState`.)_
- [x] **TEMP-024 — Formaliser l'idempotence.** Dériver les clés des effets depuis workflow ID, étape, séquence,
  source commit et digest d'entrée ; rejeter une réutilisation avec un payload différent. _(Clé canonique
  `effect-<sha256>` généralisée aux activités, sandbox et livraison SCM ; les MCP persistants comparent le
  fingerprint complet et refusent toute collision de payload.)_
- [x] **TEMP-025 — Classer les erreurs.** Distinguer erreurs métier non retryables, erreurs de contrat, saturation,
  timeout, dépendance indisponible et issue d'effet inconnue. _(`TemporalFailureClassifier` produit des types
  stables, politiques de retry explicites et un marqueur de réconciliation pour les effets incertains.)_
- [x] **TEMP-026 — Définir les politiques temporelles.** Fixer pour chaque activité start-to-close,
  schedule-to-close, heartbeat timeout, nombre de tentatives et backoff. _(Matrice V1 documentée et verrouillée
  par tests, avec schedule-to-start et absence explicite de heartbeat pour les appels courts.)_
- [x] **TEMP-027 — Utiliser l'exécuteur local comme oracle avant coupure.** Recomposer temporairement le pipeline
  historique avec les nouveaux services pour qualifier la parité, sans prévoir son maintien après bascule.
  _(`DeterministicWorkflowCoordinator` recompose les étapes extraites et `PipelineCompatibilityTest` valide le
  contrat de sortie gelé avant sa suppression au ticket TEMP-028.)_
- [ ] **TEMP-028 — Supprimer l'ordonnancement local à la coupure.** Retirer le pool interne et
  `DeterministicWorkflowCoordinator` dans le même lot de livraison que l'activation du coordinateur Temporal.

### Critères de sortie du lot 1

- [ ] Les tests historiques du pipeline passent sans changement de verdict.
- [ ] Chaque étape peut être rejouée avec la même entrée sans doubler un effet.
- [ ] Aucun appel LLM, MCP, filesystem, réseau ou horloge non déterministe ne se trouve dans le code workflow.

## 7. Lot 2 — construire le client Temporal et les workers de production

- [x] **TEMP-030 — Créer les beans obligatoires.** Construire `WorkflowServiceStubs`, `WorkflowClient`,
  `WorkerFactory` et les workers à chaque démarrage de l'orchestrateur post-bascule. _(Graphe SDK obligatoire
  créé par `TemporalRuntimeConfiguration`, avec sept workers uniques détenus par `TemporalWorkerRegistry`.)_
- [x] **TEMP-031 — Implémenter la sécurité du client.** Charger TLS/mTLS et clé API depuis des fichiers montés,
  vérifier les permissions et ne jamais injecter les secrets dans les inputs de workflow. _(TLS système, mTLS
  PKCS#8 et clé API chargés au bootstrap ; fichiers bornés, non symboliques et secrets owner-only.)_
- [x] **TEMP-032 — Enregistrer le workflow racine.** Enregistrer l'implémentation de production sur
  `ai-factory-workflows` avec un Build ID/version de déploiement explicite. _(Type immuable
  `SoftwareFactoryExecutionWorkflowV1` enregistré sur le worker racine ; Worker Deployment Version explicite et
  comportement `PINNED` sur les sept workers.)_
- [x] **TEMP-033 — Enregistrer les child workflows.** Enregistrer délégations, intégration de patch et revue
  indépendante sur les task queues décidées par l'architecture. _(Les quatre workflows de coordination sont
  enregistrés ensemble sur la file `workflow` ; les effets seront routés vers les six files spécialisées.)_
- [x] **TEMP-034 — Enregistrer les activités.** Câbler les adaptateurs contexte, LLM, sandbox, assurance,
  evidence et SCM sur leurs files respectives. _(Chaque worker spécialisé reçoit une façade à capacité minimale ;
  le worker sandbox enregistre également l'intégration de patch, sans exposer ces effets au worker workflow.)_
- [x] **TEMP-035 — Configurer la capacité.** Fixer concurrence des pollers et activités, débit, cache workflows,
  graceful shutdown et durée maximale de drainage. _(Bornes validées au démarrage et valeurs Compose explicites :
  cache/threads, pollers, concurrences, débit, drainage sticky et arrêt gracieux.)_
- [x] **TEMP-036 — Ajouter les interceptors.** Propager `traceparent`/baggage validés, identité d'exécution et
  métriques sans produire de spans lors d'un replay. _(Propagateur W3C Temporal borné branché au client ;
  interceptor worker branché à la factory avec corrélation d'identité, métriques de file et garde de replay.)_
- [x] **TEMP-037 — Exposer la readiness.** Considérer l'orchestrateur prêt en mode Temporal seulement si le
  namespace est accessible et si les workers requis sont démarrés. _(`temporalEngine` passe UP uniquement après
  accès borné au namespace et démarrage des sept workers ; ses détails distinguent infrastructure et admissions.)_
- [x] **TEMP-038 — Gérer le cycle de vie.** Démarrer la factory après l'enregistrement complet et l'arrêter avec
  drainage borné avant fermeture du client. _(`SmartLifecycle` démarre tard après validation des sept workers,
  arrête tôt, attend la durée configurée puis force l'arrêt uniquement si le drainage n'est pas terminé.)_

### Critères de sortie du lot 2

- [ ] Les task queues attendues possèdent chacune au moins un poller visible dans Temporal.
- [ ] L'application ne peut pas démarrer en mode opérationnel sans client et workers Temporal.
- [ ] Une erreur TLS, namespace ou task queue empêche clairement la readiness en mode Temporal.

## 8. Lot 3 — implémenter le workflow racine de production

- [x] **TEMP-040 — Déplacer la résolution de source dans une activité.** Résoudre et attester la branche avant tout
  travail ; figer `sourceCommit` pour toute la tentative. _(Le workflow V1 appelle `ResolveAndAttestSource` sur la
  file contexte ; URL sans credentials, identité, branche, workspace et clé d'idempotence sont validés et liés au
  commit SHA-1 avant toute coordination.)_
- [x] **TEMP-041 — Orchestrer le pipeline étape par étape.** Appeler les activités extraites dans l'ordre et
  enregistrer uniquement des références de preuves compactes dans l'historique. _(Le workflow V1 enchaîne source,
  plan, génération, application, tests, qualité, sécurité, revue et préparation ; chaque résultat ne conserve que
  les URI/digests/tailles/verdicts Evidence et chaque activité contrôle sa file spécialisée.)_
- [x] **TEMP-042 — Intégrer la réparation de patch.** Modéliser les tentatives comme une boucle workflow bornée,
  déterministe et observable, sans retry d'activité aveugle sur une erreur de patch. _(Génération LLM, validation
  sandbox et réparation sont trois activités ; le workflow conserve candidat/erreur par digest, borne à deux
  réparations et termine en `BUSINESS_REJECTION` non retryable si le patch reste invalide.)_
- [x] **TEMP-043 — Intégrer les gates.** Une gate refusée termine la tentative avec un état métier explicite et
  conserve toutes les preuves déjà produites. _(Seul `BUSINESS_REJECTION` devient `GATE_REJECTED:<étape>` ; une
  activité de projection enregistre l'état métier et la chronologie conserve les URI de preuves déjà produites,
  tandis que les pannes techniques restent des échecs Temporal.)_
- [x] **TEMP-044 — Intégrer le DAG multi-agent.** N'activer les child workflows hiérarchiques que pour les modes
  autorisés ; conserver `PIPELINE` comme comportement initial du moteur Temporal. _(`PIPELINE` est la valeur par
  défaut et rejette toute délégation/revue enfant ; seul `HIERARCHICAL_ACTIVE` autorise le scheduler de child
  workflows déjà couvert par les tests de DAG, sans couplage entre moteur Temporal et mode multi-agent.)_
- [x] **TEMP-045 — Intégrer la revue indépendante.** Lier la revue aux digests du plan, du patch, des tests, de la
  qualité, de la sécurité et du commit source. _(Le bundle est déjà lié au task/attempt/commit/manifeste ; en
  production hiérarchique il exige désormais les cinq digests exacts et les compare aux références Evidence
  réellement produites avant de lancer le child workflow indépendant.)_
- [x] **TEMP-046 — Attendre l'approbation sans thread bloqué.** Utiliser un signal Temporal et `Workflow.await`,
  avec manifeste immuable et vérification de l'approbateur côté activité/hôte. _(Le manifeste digest-bound est
  créé sur la file Evidence après les gates ; le workflow V1 attend le signal validé sans thread et conserve aussi
  un signal reçu pendant les activités, avant l'entrée dans `Workflow.await`.)_
- [x] **TEMP-047 — Encadrer l'effet SCM.** Livrer par une activité idempotente, puis réconcilier Gitea avant tout
  retry lorsque l'issue réseau est inconnue. _(La livraison ne part qu'après le signal approuvé, sur la file SCM ;
  clé liée au patch et `findExisting` côté SCM MCP réconcilient branche/PR avant création, et une absence d'accusé
  devient `EFFECT_OUTCOME_UNKNOWN` non retryable plutôt qu'une seconde création aveugle.)_
- [x] **TEMP-048 — Gérer annulation et compensation.** Annuler les activités cancellables, préserver les preuves
  et ne jamais tenter d'annuler un effet SCM déjà confirmé. _(Le signal est contrôlé entre chaque activité et
  avant SCM ; l'annulation est projetée idempotemment, les références Evidence sont conservées et la livraison ne
  démarre que depuis `APPROVED`, de sorte qu'une PR confirmée n'est jamais compensée ou supprimée.)_
- [x] **TEMP-049 — Borner l'historique.** Utiliser `continue-as-new` avant les seuils d'événements ou de taille en
  transportant uniquement l'état minimal vérifié. _(Le workflow surveille les nombres d'événements, la taille de
  l'historique et la recommandation serveur ; la continuation transporte l'index, la génération, les résultats,
  la chronologie et les seuls signaux nécessaires. Un test Temporal force trois générations et vérifie l'ordre.)_
- [x] **TEMP-050 — Versionner le déterminisme.** Couvrir toute évolution incompatible par Worker Versioning,
  nouveau type de workflow ou `Workflow.getVersion`. _(Le workflow de production possède un type `V1` immuable ;
  tous les workflows sont `PINNED` sur un Build ID explicite et une continuation ne demande `AUTO_UPGRADE` que
  lorsqu'une version de déploiement cible a changé. Les tests vérifient l'enregistrement et le replay.)_

### Critères de sortie du lot 3

- [ ] Un ticket complet atteint `WAITING_APPROVAL`, reçoit un signal, puis crée exactement une PR brouillon.
- [ ] La chronologie Temporal permet d'expliquer chaque transition exposée par l'API.
- [ ] Un replay des historiques de référence passe avec zéro erreur de non-déterminisme.

## 9. Lot 4 — commandes applicatives et signaux

- [x] **TEMP-060 — Créer un `TemporalWorkflowCoordinator`.** Implémenter `start` et `resumeAfterApproval` avec des
  stubs typés et des options de démarrage déterministes ; en faire l'unique bean `WorkflowCoordinator`.
  _(Le coordinateur démarre le type V1 sur la file workflow avec l'identité canonique, projette le Run ID/Build ID
  et adresse l'approbation au même Workflow ID ; le coordinateur local n'est plus enregistré dans Spring.)_
- [x] **TEMP-061 — Supprimer le routage de moteur.** Retirer toute sélection `LOCAL`/`TEMPORAL` et vérifier qu'un
  ticket ou une sortie de modèle ne peut modifier que le mode métier autorisé, jamais le moteur. _(Aucune propriété
  ni branche de sélection de moteur ne subsiste ; le seul bean coordinateur est Temporal. `executionMode` demeure
  un choix métier borné, transmis dans le payload du workflow et sans pouvoir sur le runtime.)_
- [x] **TEMP-062 — Signaler l'approbation.** Transformer `approve`/`approve-manifest` en signal lié à task,
  tentative, manifeste, digest, acteur et horodatage. _(La commande ne termine plus localement la tâche : elle
  signale le workflow V1. Celui-ci projette ensuite l'approbation via une activité Evidence, vérifie exactement le
  manifeste courant et son digest, puis seulement autorise l'activité SCM.)_
- [x] **TEMP-063 — Signaler les décisions humaines.** Vérifier domaine, rôle, options et object digest avant
  émission du signal. _(Le coordinateur valide la demande projetée, le rôle, le digest et l'option autorisée sans
  modifier l'état ; le workflow reçoit un signal horodaté puis une activité Evidence applique la décision à la
  projection, liée au commit source.)_
- [x] **TEMP-064 — Signaler l'annulation.** Rendre l'opération idempotente et retourner l'état projeté sans
  supposer que le workflow est déjà terminé. _(L'API valide puis signale sans mutation optimiste ; le workflow
  arrête le parcours entre activités et fait appliquer `CANCELLED` par Evidence. Une projection déjà annulée rend
  la même commande sans second signal.)_
- [x] **TEMP-065 — Implémenter retry opérateur.** Créer une nouvelle tentative Temporal liée à l'ancienne et
  supprimer le fallback vers le pipeline local ; ne jamais répéter un effet au milieu du même historique.
  _(Le retry autorisé ouvre `pipeline-N` avec un nouveau Workflow ID et un payload de filiation contenant la
  tentative précédente et le digest du motif. L'idempotence SCM inclut le nouvel attempt ID ; la route, le bouton
  et la mutation de fallback local sont supprimés.)_
- [x] **TEMP-066 — Gérer les conflits de commande.** Définir les réponses pour workflow absent, terminé,
  approbation expirée, digest périmé, signal dupliqué et projection en retard. _(Une erreur applicative 409 expose
  un code stable pour chaque conflit ; approbation, décision et annulation identiques sont idempotentes une fois
  projetées, tandis que les divergences et expirations ferment la commande avant signal.)_
- [x] **TEMP-067 — Auditer les commandes.** Journaliser l'intention et le résultat avec corrélation, sans contenu
  sensible ni secret. _(Approbation, décision, annulation et retry produisent `COMMAND_INTENT` puis
  `COMMAND_ACCEPTED` ou `COMMAND_REJECTED`, corrélés à task/attempt/opération/objet. Motifs libres, réponses et
  messages d'exception ne sont jamais écrits.)_

### Critères de sortie du lot 4

- [ ] Toutes les routes de commande existantes fonctionnent exclusivement via Temporal.
- [ ] Un signal dupliqué ne change pas deux fois l'état et ne déclenche pas deux effets.
- [ ] L'API distingue acceptation de commande, application au workflow et mise à jour de projection.

## 10. Lot 5 — projection durable et reconstruction

- [x] **TEMP-070 — Ajouter une base applicative dédiée.** Déployer `orchestrator-db` dans Compose ; ne pas
  réutiliser `temporal-db` ni ses identifiants. _(PostgreSQL 16 possède son volume, son compte, son mot de passe,
  son healthcheck et sa dépendance Compose propres sur le réseau workflow privé ; `make init` génère le secret.)_
- [x] **TEMP-071 — Versionner le schéma.** Créer les migrations pour tâches, tentatives, runs, transitions,
  délégations, artefacts, contradictions, décisions, actions humaines et effets en attente. _(Les migrations
  V001–V009 couvrent le modèle, ses filiations, contraintes et versions optimistes ; Flyway les charge depuis le
  JAR avant le démarrage et l'orchestrateur reçoit uniquement les identifiants de sa base dédiée.)_
- [x] **TEMP-072 — Implémenter `PostgresTaskMemory`.** Fournir lectures et écritures transactionnelles avec
  verrouillage optimiste et contraintes d'unicité. _(Le bean applicatif persiste atomiquement les métadonnées
  PostgreSQL et une référence vers le snapshot complet chiffré par Evidence MCP ; les lectures vérifient URI,
  digest et statut avant reconstruction, et les versions concurrentes sont rejetées.)_
- [x] **TEMP-073 — Persister l'admission avant le démarrage.** Utiliser une outbox ou une procédure de
  réconciliation afin d'éviter l'état « ligne créée, workflow absent » et l'inverse. _(L'admission écrit la tâche,
  son snapshot Evidence et une intention `PENDING` dans une transaction avant tout appel Temporal ; un réconciliateur
  borné au démarrage puis périodique réessaie avec un workflow ID non réutilisable et clôt l'intention avec le run ID.)_
- [x] **TEMP-074 — Projeter les événements.** Mettre à jour le read model depuis des activités de projection
  idempotentes ou depuis l'historique Temporal avec un curseur durable. _(Toutes les activités projettent via leur
  activity ID stable ; PostgreSQL committe atomiquement snapshot vérifié, clé d'idempotence et position monotone,
  de sorte qu'un retry déjà appliqué ne modifie ni état, ni compteurs, ni transitions.)_
- [x] **TEMP-075 — Détecter le retard.** Exposer l'âge et la position de projection et signaler une vue
  potentiellement obsolète sans inventer un succès. _(L'API `/api/tasks/{id}/projection` expose tentative,
  curseur, event ID, instant et âge ; le seuil est configurable, et l'interface affiche un avertissement explicite
  lorsque la vue est potentiellement obsolète ou lorsque sa fraîcheur ne peut pas être déterminée.)_
- [x] **TEMP-076 — Reconstruire une tâche.** Rejouer l'historique Temporal et vérifier les digests Evidence avant
  remplacement atomique de la projection. _(La source SDK retrouve l'entrée, le commit résolu et les références
  produites par les activités ; le rebuilder refuse toute divergence de lignée, URI, digest ou statut, puis le store
  PostgreSQL remplace en une transaction la tâche, le run, les délégations et les métadonnées Evidence vérifiées.)_
- [x] **TEMP-077 — Reconstruire toutes les projections.** Ajouter une commande opérateur bornée, observable et
  réentrante avec mode dry-run. _(La commande `POST /api/operations/projections/rebuild` traite au plus 100 tâches
  courantes, accepte un curseur de reprise, propose une validation sans écriture, isole les erreurs par tâche et
  publie compteurs et audit sans contenu sensible ; relancer une page remplace les mêmes lignes atomiquement.)_
- [x] **TEMP-078 — Persister la séquence des tickets.** Remplacer le compteur JVM `AF-xxxx` par une séquence
  durable sans collision après redémarrage. _(PostgreSQL alloue désormais chaque numéro via
  `task_ticket_number_seq` et impose unicité, format et non-nullité ; la migration numérote aussi les lignes
  existantes, tandis que le générateur mémoire n'est conservé que pour les services construits en test unitaire.)_
- [x] **TEMP-079 — Migrer les tâches locales utiles.** Utiliser `LegacyTaskMigrator`, marquer la provenance et ne
  pas fabriquer d'historique Temporal pour une ancienne exécution locale. _(Les anciennes tâches `PR_CREATED` et
  `FAILED` sont archivées dans Evidence, importées atomiquement et idempotemment avec leur numéro ; la table de
  provenance et le mode `LEGACY_LOCAL` les distinguent, le lecteur revérifie le digest, et aucun workflow run n'est créé.)_

### Critères de sortie du lot 5

- [ ] Un redémarrage de l'orchestrateur conserve la liste et le détail des tâches.
- [ ] Une projection supprimée peut être reconstruite depuis Temporal et Evidence MCP.
- [ ] Un écart de digest arrête la reconstruction et produit une alerte de sécurité.

## 11. Lot 6 — observabilité, exploitation et sécurité

- [x] **TEMP-080 — Corréler les signaux.** Ajouter workflow ID, run ID, task ID, attempt ID, task queue, workflow
  type et activity type aux traces, métriques et logs selon les règles de cardinalité. _(L'intercepteur enrichit
  chaque observation et le MDC avec les huit dimensions Temporal ; namespace, queue et types restent des tags
  métriques bornés, tandis que task, attempt, workflow et run IDs sont réservés à la corrélation haute cardinalité.)_
- [x] **TEMP-081 — Mesurer les files.** Collecter backlog, schedule-to-start, retries, timeouts, pollers,
  saturation et workflows bloqués en attente humaine. _(Le reporter Micrometer natif du SDK publie pollers, slots,
  erreurs et latences schedule-to-start ; une sonde bornée `DescribeTaskQueue` complète backlog et pollers par
  périmètre/type, l'intercepteur compte retries et timeouts, et PostgreSQL alimente la jauge d'attente humaine.)_
- [x] **TEMP-082 — Compléter le dashboard SigNoz Temporal.** Ajouter santé client/worker, files par périmètre,
  erreurs d'activités et liens profonds vers Temporal UI. _(Le dashboard généré couvre désormais les requêtes
  client, pollers et slots worker, backlog/pollers applicatifs, schedule-to-start, erreurs/retries/timeouts et attente
  humaine ; namespace, workflow ID et run ID construisent un lien direct vers l'historique Temporal UI.)_
- [x] **TEMP-083 — Ajouter les alertes.** Couvrir absence de poller, backlog durable, erreur non déterministe,
  projection en retard, activité bloquée et échec de continue-as-new. _(Six règles SigNoz dédiées s'appuient sur
  des métriques explicites et bornées ; la sonde publie le retard de projection, les workflows annoncent leur
  rollover, et les 15 alertes métier/Temporal ont été déclenchées par une fixture OTLP contre SigNoz local.)_
- [x] **TEMP-084 — Protéger les données.** Vérifier qu'aucun secret, patch complet, log volumineux ou donnée
  confidentielle n'entre dans les inputs, search attributes ou memo Temporal. _(Une garde fail-closed contrôle tout
  démarrage/signal avant le SDK et refuse secrets, credentials URI, diff brut, contenu classifié, texte/collection ou
  payload hors bornes ; besoin, objectif, question et motif deviennent des digests, les contenus restent dans Evidence,
  et les anciens adapters textuels agent/MCP ne sont plus enregistrés.)_
- [x] **TEMP-085 — Définir les Search Attributes.** N'enregistrer que les dimensions nécessaires, bornées et non
  sensibles ; versionner leur création locale. _(Le schéma v1 ne contient que task ID, attempt ID, repository ID et
  mode d'exécution en `Keyword` ; une garde exige le jeu exact et des valeurs bornées, et le bootstrap local crée les
  attributs de façon idempotente depuis le manifeste versionné.)_
- [x] **TEMP-086 — Sauvegarder et restaurer.** Tester sauvegarde cohérente de `temporal-db`, `orchestrator-db` et
  Evidence MCP, puis restauration dans l'ordre documenté. _(Les écrivains sont gelés avant les dumps et l'archive,
  un manifeste SHA-256 protège le jeu ; le test restaure sans réseau et sans toucher les volumes actifs dans l'ordre
  Evidence, autorité Temporal, puis projection orchestrateur.)_
- [x] **TEMP-087 — Tester la rétention.** Vérifier expiration des historiques, conservation légale des preuves et
  comportement de la projection lorsque l'historique n'est plus disponible. _(Le namespace local est contrôlé à
  7 jours ; un legal hold Evidence autorisé, borné et audité bloque réellement la purge en fail-closed ; une histoire
  expirée produit une erreur stable par tâche sans remplacer la projection par des faits incomplets.)_
- [x] **TEMP-088 — Mettre à jour les runbooks.** Compléter indisponibilité Temporal, worker défaillant, saturation,
  rollback, projection incohérente et effet à issue inconnue. _(L'index opérateur relie six procédures fail-closed ;
  elles actent la bascule franche sans fallback local, le retour à un build compatible, la reconstruction atomique et
  la réconciliation en lecture seule de tout effet incertain.)_

### Critères de sortie du lot 6

- [x] Une tâche est navigable de l'interface vers SigNoz, Temporal UI, Evidence MCP et Gitea. _(La fiche expose des
  liens corrélés au task/workflow/run, un endpoint de métadonnées Evidence sans contenu brut et le dépôt Gitea.)_
- [x] Les alertes sont testées par injection de panne et reviennent automatiquement à l'état normal. _(La fixture
  OTLP évalue les 15 règles métier/Temporal en état déclenché, injecte des compteurs stables et jauges saines après la
  fenêtre PromQL maximale, puis exige que les 15 conditions ne retournent plus aucune série.)_
- [x] La restauration conserve l'idempotence et n'entraîne aucun rejeu SCM non autorisé. _(Le snapshot gèle et
  restaure désormais `scm-delivery-state` avant Temporal ; le test SCM recrée explicitement le store depuis le disque
  et prouve que la même clé retourne le résultat existant sans second appel au backend.)_

## 12. Lot 7 — stratégie de tests

### 12.1 Tests unitaires et d'architecture

- [x] Tester validation des options client, namespace, queues, TLS et secrets par fichier. _(Les tests de propriétés,
  sécurité client, Compose et version SDK valident les bornes, les sept queues, TLS conditionnel et les fichiers de
  secrets owner-only sans exposer leur contenu.)_
- [x] Tester l'absence de routage `LOCAL`/`TEMPORAL` et le refus de démarrer ou d'admettre sans Temporal. _(Temporal
  est le seul `@Component` du port de coordination ; readiness et admission échouent fermées si le worker, le service
  ou le namespace ne répondent pas, sans branche de sélection de moteur.)_
- [x] Tester identifiants, clés d'idempotence, classification des erreurs et politiques de retry. _(Les identités sont
  déterministes et bornées, les effets survivent à un store recréé, les erreurs pilotent retry/non-retry et les profils
  par périmètre valident délais, heartbeat et tentatives.)_
- [x] Interdire par test d'architecture réseau, filesystem, horloge système, thread, random et client MCP dans les
  implémentations de workflow. _(Le scan exhaustif des implémentations interdit I/O, HTTP/MCP, processus, horloges,
  aléatoire, threads/concurrence Java et clients Temporal ; les effets passent uniquement par des Activities.)_
- [x] Tester la taille maximale des inputs/résultats et l'externalisation des contenus vers Evidence MCP. _(La garde
  64 Kio contrôle démarrages, signaux et résultat racine ; les résultats d'étape ne transportent que URI, digest,
  taille, statut et verdict d'artefacts stockés dans Evidence.)_
- [x] Tester les transitions et commandes invalides sur la projection. _(Transitions hors ordre, événements dupliqués,
  version optimiste périmée, approbation/décision/annulation/retry invalides et remplacement atomique défaillant sont
  tous refusés sans état partiel.)_

### 12.2 Tests Temporal embarqués

- [x] Tester le parcours nominal jusqu'à l'approbation et la livraison. _(Le workflow V1 de production traverse les
  sept task queues avec des Activities embarquées, attend le manifeste exact, reçoit le signal d'approbation et
  termine `PR_CREATED` avec une seule livraison et uniquement des URI Evidence.)_
- [x] Tester rejet de chaque gate et préservation des preuves partielles. _(Les gates test, qualité, sécurité et revue
  sont rejetés tour à tour ; chacun termine sans livraison, enregistre le gate exact et conserve dans le résultat et
  la query Evidence tous les artefacts complets produits auparavant.)_
- [x] Tester retries déterministes, heartbeat, timeout et activité non retryable. _(Les tests embarqués imposent deux
  tentatives après timeout en ignorant la réponse tardive, reprennent le même job sandbox via les détails de
  heartbeat et prouvent qu'une erreur métier `nonRetryable` ne déclenche qu'une seule tentative.)_
- [x] Tester signaux reçus avant et pendant `Workflow.await`. _(Un `signalWithStart` atomique prouve que
  l'approbation reçue avant l'attente est mémorisée ; un second scénario attend explicitement
  `WAITING_APPROVAL`, ignore un signal non lié puis reprend sur le signal valide.)_
- [x] Tester annulation en clonage, LLM, sandbox, attente humaine et livraison. _(Des barrières synchronisées injectent
  l'annulation dans chaque phase : les quatre phases avant effet SCM terminent `CANCELLED` avec preuve persistée ;
  une livraison déjà démarrée finit exactement une fois et restitue `PR_CREATED` plutôt qu'un faux état annulé.)_
- [x] Tester `continue-as-new` et propagation de l'état minimal. _(Trois délégations avec une limite d'une par run
  traversent deux `continue-as-new` sans perte ni doublon ; le contrat transmis est verrouillé aux index/génération,
  résultats compacts, chronologie et signaux reçus, sans exigence, URL, workspace ni contenu d'artefact.)_
- [x] Tester child workflows parallèles, échec en cascade et revue indépendante. _(Les tests démarrent tous les
  enfants prêts avant toute attente, propagent transitivement l'échec aux seuls dépendants, annulent enfant et
  activité avec la racine, et imposent une revue indépendante lancée par la racine après consolidation.)_
- [x] Tester l'unicité de l'effet SCM après perte d'accusé de réception. _(La première tentative crée l'effet puis
  perd son accusé ; la tentative Temporal suivante et même un service SCM recréé récupèrent le résultat par la clé
  d'idempotence persistée, avec deux appels d'activité mais un seul effet externe.)_

### 12.3 Tests de replay et compatibilité

- [x] Versionner des historiques JSON de référence pour succès, échec, attente, annulation et continue-as-new.
  _(Le corpus V1 contient cinq historiques JSON réellement émis par `SoftwareFactoryWorkflowImpl`, avec un
  générateur explicite opt-in pour empêcher tout rafraîchissement silencieux des preuves.)_
- [x] Exécuter automatiquement le replay contre chaque nouvelle version du worker. _(Un test paramétré découvre le
  corpus V1 dans les ressources de test et rejoue ses cinq historiques avec `WorkflowReplayer` contre
  l'implémentation courante à chaque exécution Maven.)_
- [x] Tester un ancien worker et un nouveau worker simultanément avec Build IDs compatibles. _(Deux registres
  coexistants exposent les sept mêmes task queues sous le même Worker Deployment, avec Build IDs immuables distincts
  et comportement `PINNED`, ce qui permet le drainage de l'ancien pendant l'activation du nouveau.)_
- [x] Refuser la livraison d'une image worker lorsque le replay échoue. _(Le Dockerfile exécute le corpus de replay
  avant tout `mvn package`, `make build` dépend d'une cible `temporal-replay`, et un test d'architecture verrouille
  l'ordre de cette barrière fail-closed.)_

### 12.4 Tests d'intégration Docker Compose sur macOS

- [x] Démarrer une stack neuve et vérifier namespace, pollers, UI et readiness. _(La stack Compose applique les
  14 migrations Flyway avant admission, crée le namespace et ses quatre Search Attributes, active explicitement
  le Build ID local après enregistrement des sept files, puis `make test-temporal-compose` contrôle l'UI
  loopback, la readiness et les sept pollers. Validation macOS Docker Desktop exécutée avec succès.)_
- [x] Soumettre un ticket réel et vérifier son apparition dans Temporal UI. _(Le ticket réel `AF-0006`
  (`a0bd34b9`) a été admis par `POST /api/tasks` avec le workflow
  `ai-factory/a0bd34b9/pipeline-1` et le run `01a0741e-48f3-7182-be6e-0d6f7dc8db44`. L'API de Temporal UI et la
  CLI retrouvent exactement ces identifiants et les Search Attributes. `make test-temporal-ticket-ui` rend ce
  contrôle reproductible.)_
- [x] Tuer puis recréer l'orchestrateur pendant chaque phase critique. _(La cible
  `make test-temporal-orchestrator-restarts` soumet un ticket isolé pour chacune des huit phases, recrée
  l'orchestrateur et le worker d'activation, puis vérifie la conservation du `workflowId` et du `runId` avant
  annulation. La projection d'état marque désormais explicitement le début et la fin de chaque activité, le client
  MCP emploie le transport HTTP JDK depuis les threads d'activité et Compose accorde 40 s au drainage Temporal.)_
- [x] Tuer un worker pendant une activité avec heartbeat et vérifier la reprise. _(Le checkpoint Temporal lie clé
  d'effet, opération et `executionId` sandbox ; toute nouvelle tentative reprend ce job au lieu de le soumettre à
  nouveau. `make test-temporal-worker-heartbeat` fige le runner de tests, vérifie le heartbeat côté serveur, envoie
  `SIGKILL` au worker, puis contrôle la tentative 2 après `TIMEOUT_TYPE_HEARTBEAT`, le même Run ID et une unique
  soumission sandbox. Validation Docker Desktop : workflow `ai-factory/8aa2e0fb/pipeline-1`, run
  `01a07461-2959-774d-aa0e-48c42e3e9da0`.)_
- [x] Redémarrer Temporal puis PostgreSQL en préservant les volumes. _(La cible
  `make test-temporal-storage-restarts` empreinte une projection et un historique clos, redémarre d'abord le service
  Temporal puis `temporal-db`, et compare après chaque reprise le digest des 63 événements. Le nom et la date de
  création du volume `ai-software-factory_temporal-db-data`, le Run ID et la projection sont restés identiques.)_
- [x] Simuler indisponibilité MCP, LiteLLM, Gitea, SonarQube, Artifactory et Collector. _(La cible
  `make test-temporal-dependency-outages` arrête réellement chaque service, constate son inaccessibilité depuis le
  réseau de l'orchestrateur, vérifie que le control plane reste vivant, puis attend et sonde la récupération. Pour
  LiteLLM, l'API expose aussi `cloudAvailable=false` ; le scénario Collector contrôle en plus la file persistante,
  l'indisponibilité du backend SigNoz et le rejet d'un payload OTLP invalide.)_
- [x] Vérifier le pipeline complet : patch, tests, Sonar, Trivy, revue, approbation et une seule PR. _(La cible
  `make test-temporal-pipeline-delivery` contrôle les sept preuves complètes et leurs verdicts, approuve le digest
  exact du manifeste, attend `PR_CREATED`, compare le Run ID et compte les PR via l'API Gitea. Une seconde
  approbation ne crée aucun doublon. Validation réelle : tâche `2500dfb2`, run
  `01a0746e-e3a8-7319-a558-975cf0bbd8a2`, PR `aiadmin/customer-api#2`.)_
- [x] Vérifier que `docker compose down` puis `up` conserve tâches et historiques. _(La cible
  `make test-temporal-compose-cycle` empreinte historique, projection et identités des volumes PostgreSQL, exécute
  un vrai `down --remove-orphans` sans `-v`, recrée toute la stack, puis compare les données et la vue API.
  Validation réelle : tâche livrée `2500dfb2`, run `01a0746e-e3a8-7319-a558-975cf0bbd8a2`, 93 événements et deux
  volumes PostgreSQL inchangés.)_

### 12.5 Tests de charge et de durée

- [x] Soumettre des tickets concurrents au-delà de la capacité worker et mesurer le backpressure. _(La cible
  `make test-temporal-backpressure` recrée temporairement les workers avec capacité 1 et débit 0,1 activité/s,
  soumet six tickets en parallèle, exige un Run ID pour chacun, mesure le backlog gRPC exposé par Micrometer, puis
  annule la charge et restaure la capacité nominale. Mesure locale : 6 admissions en 1 920 ms, backlog maximal 7
  sur `context`.)_
- [x] Tester les limites globales et par task queue. _(La cible `make test-temporal-capacity-limits` vérifie les
  bornes de configuration, l'application uniforme des limites de concurrence et de débit aux sept task queues,
  les métriques de saturation, ainsi que les quotas globaux et par tâche du sandbox. Les 28 tests ciblés passent.)_
- [x] Tester une attente humaine supérieure à un redémarrage et à une rotation de worker. _(La cible
  `make test-temporal-human-wait-rotation` maintient un ticket en `WAITING_APPROVAL`, redémarre l'orchestrateur,
  enregistre et rend courante une nouvelle version sur les sept files sans retirer l'ancienne version épinglée,
  puis approuve et restaure la version nominale. Validation réelle : tâche `4d111f8d`, run
  `01a07481-a706-7031-845b-f14d3623c8f7`, attente 35 s, livraison `PR_CREATED` sur le même Run ID.)_
- [x] Tester rétention, purge et reconstruction sur un jeu représentatif. _(La cible
  `make test-temporal-retention-rebuild` contrôle la rétention réelle de 7 jours, la purge Evidence avec legal
  hold et comportement fail-closed, puis la reconstruction atomique et réentrante pour les statuts `APPROVED`,
  `REJECTED`, `CANCELLED`, `FAILED` et `TIMED_OUT`, y compris l'arrêt sur divergence de digest. Le contrôle live
  et les 19 tests ciblés passent.)_

## 13. Lot 8 — bascule franche et complète

La bascule est réalisée en une seule fenêtre de changement. Aucun ticket n'est routé en shadow, en canary ou vers
deux moteurs simultanément. La qualification complète est terminée avant la coupure ; après réouverture, Temporal
est immédiatement l'unique moteur de toutes les admissions.

- [x] **TEMP-100 — Capturer la baseline avant coupure.** Versionner résultats, coûts, durées, digests et états du
  pipeline local sur un corpus fixe, uniquement comme preuve de comparaison hors production. _(La cible
  `make temporal-cutover-baseline` vérifie le corpus figé de 20 cas, ses états, 543 041 ms de durée cumulée,
  134 365 tokens, le statut de coût `UNAVAILABLE_NOT_ZERO`, les objets Git et les SHA-256 des deux artefacts. Les
  chemins des preuves archivées ont été remis en cohérence sans modifier leurs digests.)_
- [x] **TEMP-101 — Geler le périmètre.** Interdire tout changement de workflow, activité, contrat, prompt, modèle
  ou infrastructure entre la qualification finale et la fin de la fenêtre de bascule. _(Le manifeste
  `resources/temporal/cutover-freeze-v1.yaml` fige onze objets Git au commit `f999dee`. La cible
  `make temporal-cutover-freeze` refuse toute modification suivie ou tout ajout non suivi dans ces chemins ; le
  contrôle passe sur le périmètre qualifié.)_
- [x] **TEMP-102 — Qualifier la release complète hors trafic.** Exécuter tests fonctionnels, replay, charge,
  redémarrages, partitions réseau, sauvegarde, restauration et rollback sur l'artefact exact à déployer.
  _(La barrière `make qualify-temporal-cutover` a validé 16/16 étapes en 1 386 s sur le commit gelé `9698fa3`
  et l'image immuable `sha256:ef7b416e…604e`. Tests propres, replay, sept pollers, backpressure, partition,
  huit phases de redémarrage, heartbeat, stockage, dépendances, sauvegarde/restauration, livraison exactement une
  fois observable, rotation de worker et cycle Compose sont verts. Preuve :
  `docs/evidence/temporal/TEMP-102-qualification-release-2026-09-06.md`.)_
- [x] **TEMP-103 — Obtenir l'autorisation de coupure.** Exiger les validations produit, architecture, sécurité et
  exploitation sur la matrice de preuves complète. _(David Beaumont, autorité unique du POC local, a approuvé les
  quatre responsabilités le 2026-09-06 sur la release exacte `9698fa30aa16a43af5b2d4f56b82f1953eb95080`.
  Le gate signé est archivé dans `docs/qualification/temporal/GATE-TEMP-103-CUTOVER.md`.)_
- [x] **TEMP-104 — Fermer toutes les admissions.** Refuser temporairement `POST /api/tasks`, afficher la maintenance
  dans l'interface et attendre la fin ou l'annulation contrôlée de chaque tâche locale active. _(Le verrou durable
  PostgreSQL est fermé à la révision `2` avec le motif `temporal_cutover` ; l'API renvoie `503`, l'interface expose
  la maintenance, l'outbox ne contient aucune admission en attente et Temporal ne contient aucun workflow ouvert.
  Les 560 tests Java sont verts. Preuve : `docs/evidence/temporal/TEMP-104-admissions-closed-2026-09-06.md`.)_
- [x] **TEMP-105 — Sauvegarder les autorités.** Sauvegarder Gitea, Evidence MCP, configuration, workspaces utiles et
  bases ; vérifier la restauration avant de poursuivre. _(La sauvegarde privée de 18M couvre Temporal, projection,
  Evidence, idempotence SCM, Gitea, workspaces et configuration. Tous les SHA-256 et les nombres d'objets ont été
  vérifiés après restauration isolée ; le verrou restauré reste fermé et aucun volume actif n'a été modifié.
  Preuve : `docs/evidence/temporal/TEMP-105-authorities-backup-restore-2026-09-06.md`.)_
- [x] **TEMP-107 — Retirer le chemin local.** Supprimer `DeterministicWorkflowCoordinator`, son pool de threads,
  les flags de sélection et toute route de fallback dans la release de bascule. _(Le coordinateur local et son
  traceur asynchrone ont été supprimés ; `TemporalWorkflowCoordinator` est l'unique implémentation de production,
  sans flag de sélection ni route de repli. Les 557 tests Java sont verts et le gel du périmètre Temporal reste
  valide. Preuve : `docs/evidence/temporal/TEMP-107-local-path-removed-2026-09-06.md`.)_
- [ ] **TEMP-103R — Réautoriser l'artefact final.** Approuver explicitement le commit et les images qui incluent le
  verrou d'admission et le retrait du chemin local avant tout déploiement. _(Candidat préparé au commit
  `a589f5c4c93088650bc7f46a13655cf10b92bf94` ; dossier de delta :
  `docs/evidence/temporal/TEMP-103R-final-candidate-2026-09-06.md`.)_
- [ ] **TEMP-106 — Déployer atomiquement.** Déployer dans la même fenêtre Temporal obligatoire, workers,
  coordinateur, projection PostgreSQL, migrations, API, interface, dashboards et alertes.
- [ ] **TEMP-108 — Vérifier avant réouverture.** Contrôler schémas, namespace, Build IDs, pollers, task queues,
  readiness, projection, Evidence MCP, SigNoz et Temporal UI.
- [ ] **TEMP-109 — Exécuter un smoke test de coupure.** Soumettre un ticket synthétique pendant la maintenance,
  vérifier le parcours complet et supprimer uniquement ses artefacts explicitement jetables.
- [ ] **TEMP-110 — Ouvrir toutes les admissions.** Autoriser simultanément tous les dépôts et toutes les catégories
  de tickets sur Temporal, sans pourcentage, allow-list transitoire ou double exécution.
- [ ] **TEMP-111 — Surveiller la fenêtre renforcée.** Maintenir l'équipe de rollback disponible et appliquer les
  seuils d'arrêt globaux, sans router une partie du trafic vers l'ancien moteur.

### Critères de coupure

- [ ] Toutes les preuves obligatoires sont validées avant fermeture des admissions.
- [ ] Aucune tâche locale active ne subsiste au moment du déploiement.
- [ ] Zéro PR, commit ou preuve dupliquée pendant les tests de panne et de restauration.
- [ ] Tous les historiques de référence sont rejouables par l'image exacte déployée.
- [ ] Le smoke test post-déploiement passe avant la réouverture générale.
- [ ] Après réouverture, 100 % des nouvelles tâches possèdent un workflow ID Temporal et aucune ne possède un run
  local.

## 14. Procédure de rollback de version Temporal

- [ ] Fermer immédiatement toutes les nouvelles admissions.
- [ ] Identifier tous les workflows Temporal ouverts, leur Build ID, leur phase et leurs effets en attente.
- [ ] Redéployer la dernière image de workers Temporal compatible avec les historiques ouverts.
- [ ] Conserver simultanément les Build IDs nécessaires au drainage lorsque plusieurs versions ont déjà exécuté
  des workflows.
- [ ] Ne jamais relancer une tentative Temporal dans un coordinateur local supprimé.
- [ ] Créer une nouvelle tentative Temporal liée lorsqu'une demande doit être reprise après stabilisation.
- [ ] Réconcilier toute activité SCM à issue inconnue avec Gitea avant une nouvelle commande.
- [ ] Préserver `temporal-db-data`, `orchestrator-db-data`, Evidence MCP et workspaces pendant l'incident.
- [ ] Vérifier la cohérence de la projection après stabilisation et reconstruire uniquement depuis les autorités.
- [ ] Garder les admissions fermées si aucune version worker compatible ne peut être restaurée ; ne jamais réactiver
  l'ancien moteur pour contourner l'incident.
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
TEMP-100..111 coupure franche et ouverture générale
```

- [ ] Traiter chaque ticket dans un commit dédié de la forme `feat(temporal): TEMP-xxx ...`.
- [ ] Ne cocher un ticket qu'après tests associés, mise à jour documentaire et commit réussi.
- [ ] Inscrire le hash du commit et la preuve de validation à côté de chaque case cochée.
- [ ] Ne pas mélanger une modification de workflow déterministe avec une mise à jour de dépendance ou
  d'infrastructure non liée.
- [ ] Créer un historique de replay avant toute modification incompatible du code workflow.

## 16. Matrice de preuves obligatoire

| Preuve | Qualification avant coupure | Fenêtre de coupure | Validation après coupure |
|---|---:|---:|---:|
| Tests unitaires et architecture | [ ] | [ ] | [ ] |
| Tests Temporal embarqués | [ ] | [ ] | [ ] |
| Replay des historiques versionnés | [ ] | [ ] | [ ] |
| Parcours Docker Compose macOS | [ ] | [ ] | [ ] |
| Reprise après arrêt orchestrateur | [ ] | [ ] | [ ] |
| Reprise après arrêt worker | [ ] | [ ] | [ ] |
| Signaux humains idempotents | [ ] | [ ] | [ ] |
| Effet SCM exactement une fois observable | [ ] | [ ] | [ ] |
| Reconstruction PostgreSQL | [ ] | [ ] | [ ] |
| Sauvegarde et restauration | [ ] | [ ] | [ ] |
| Dashboards et alertes SigNoz | [ ] | [ ] | [ ] |
| Rollback de version Temporal exécuté | [ ] | [ ] | [ ] |

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
- [ ] La coupure ouvre directement 100 % des admissions sur Temporal, sans phase intermédiaire.
- [ ] Le rollback vers une version compatible des workers Temporal a été exécuté sans perte ni double effet.
- [ ] Le coordinateur local, ses flags et ses routes de fallback ont été supprimés.
- [ ] La documentation d'état courant ne présente plus Temporal comme seulement disponible ou non câblé.

## 18. Hors périmètre de cette migration

- [ ] Ne pas fusionner automatiquement les Pull Requests créées.
- [ ] Ne pas utiliser la base interne Temporal comme base métier.
- [ ] Ne pas activer le mode hiérarchique simplement parce que le moteur Temporal est actif.
- [ ] Ne pas exposer Temporal gRPC publiquement pour faciliter le diagnostic local.
- [ ] Ne pas considérer le succès des tests embarqués comme une qualification de production.
- [ ] Ne pas conserver de shadow, canary, routage mixte ou coordinateur local après la coupure.
