# Plan de migration — agents A2A orchestrés par Temporal

> Statut : `À EXÉCUTER`
>
> Cible normative : protocole A2A `1.0`, SDK Java officiel qualifié et épinglé avant implémentation.
>
> Stratégie de mise en production : bascule franche. La qualification peut être réalisée hors production, mais
> aucun routage mixte, fallback vers des appels Java directs ou maintien durable de deux moteurs de communication
> n'est autorisé après la coupure.

## 1. Objectif

Remplacer les appels directs entre les rôles d'agents actuellement chargés dans la JVM de l'orchestrateur par des
échanges conformes au protocole Agent2Agent (A2A), tout en conservant Temporal comme autorité unique de
l'orchestration, des retries, des timers, des annulations et de la reprise après incident.

La migration doit préserver :

- le comportement métier et les contrats multi-agents existants ;
- la hiérarchie et les droits décrits par `resources/agents/catalog-v1.yaml` ;
- les gates humaines, sécurité, qualité et revue indépendante ;
- la séparation entre A2A, utilisé pour les échanges agent-à-agent, et MCP, utilisé pour les outils ;
- Evidence MCP comme stockage des artefacts volumineux et de leurs digests ;
- OpenTelemetry et SigNoz comme chaîne d'observabilité ;
- le développement et les tests locaux sur macOS avec Docker Compose ;
- le déploiement cible sur GKE sans modifier les contrats A2A.

## 2. Principes d'architecture non négociables

- [x] **A2A-001 — Consigner l'architecture dans une ADR.** Décrire A2A comme plan de données des interactions
  d'agents et Temporal comme plan de contrôle de l'exécution. _(Décision consignée dans `ADR-A2A-001` : Temporal
  reste seul ordonnanceur et A2A devient l'unique frontière d'invocation des agents après la coupure.)_
- [x] **A2A-002 — Conserver Temporal comme autorité d'orchestration.** Seul le workflow Temporal décide quels
  agents sont exécutés, dans quel ordre, avec quels délais, budgets, retries et règles d'annulation. _(Autorité,
  frontière de décision et topologie normatives fixées dans `ADR-A2A-001`, en cohérence avec `ADR-TEMP-001`.)_
- [x] **A2A-003 — Interdire la délégation réseau autonome.** Un agent peut retourner une intention de délégation,
  mais il ne peut pas contacter directement un autre agent pour contourner le DAG, les budgets ou les gates ; le
  workflow valide l'intention puis crée la prochaine interaction A2A. _(La frontière de décision et l'alternative
  « délégations A2A directes » rejetée sont consignées dans `ADR-A2A-001`.)_
- [ ] **A2A-004 — Supprimer les invocations d'agents en mémoire.** Après la coupure, aucun workflow, activité ou
  service de coordination ne doit appeler directement `SupervisorAgent`, `ArchitectureAgents`, `CodeAgent`,
  `TestAgents`, `SecurityAgents`, `IndependentReviewerAgent` ou leurs sous-agents.
- [x] **A2A-005 — Maintenir la distinction A2A/MCP.** A2A transporte messages, états et références d'artefacts
  entre agents ; MCP reste le protocole d'accès aux capacités `context`, `sandbox`, `assurance`, `evidence` et
  `scm`. _(Responsabilités et alternative MCP rejetée formalisées dans `ADR-A2A-001`.)_
- [x] **A2A-006 — Utiliser des artefacts, pas des messages, pour les résultats.** Les sorties métier validées sont
  publiées comme artefacts A2A structurés ; les messages servent aux instructions, statuts et demandes de
  complément. _(Règle de transport des résultats adoptée dans `ADR-A2A-001`.)_
- [x] **A2A-007 — Ne pas placer de contenu volumineux dans A2A ou Temporal.** Patchs, journaux, rapports et SBOM
  restent dans Evidence MCP ; A2A et Temporal ne transportent que des références URI internes, digests, tailles,
  types de média et verdicts. _(Autorités et règle de compacité fixées dans `ADR-A2A-001`.)_
- [x] **A2A-008 — Fonctionner en fail-closed.** Une Agent Card invalide, une identité non autorisée, une version
  incompatible ou l'indisponibilité d'un agent suspend ou échoue explicitement le workflow ; aucun fallback local
  n'est permis. _(Politique de coupure et rollback sans chemin direct adoptée dans `ADR-A2A-001`.)_

## 3. Architecture cible

```text
API / UI
   |
   v
Orchestrateur applicatif
   |
   v
Temporal : SoftwareFactoryExecutionWorkflowV1
   |
   +--> activité A2A Dispatch/Reconcile/Cancel
           |
           |  A2A 1.0 + identité de service + traceparent
           v
       Endpoint A2A de l'agent
           |
           +--> projection durable de la tâche A2A
           +--> AgentTaskWorkflowV1 sur Temporal
                   |
                   +--> AgentRuntime du rôle
                   +--> outils MCP autorisés pour le rôle
                   +--> artefacts dans Evidence MCP
           |
           +--> notification A2A signée vers l'orchestrateur
                       |
                       +--> signal Temporal idempotent
```

La chaîne de délégation devient :

```text
Supervisor --artefact d'intention--> Temporal --SendMessage A2A--> spécialiste
spécialiste --artefact de résultat--> Temporal --nouvelle décision--> Supervisor
```

Temporal reste donc le seul ordonnanceur. A2A rend chaque agent adressable et interopérable sans créer un réseau
de délégations incontrôlées.

## 4. Répartition des autorités

| Donnée ou décision | Autorité | Copie/projection autorisée |
|---|---|---|
| DAG, ordre, retries, timers et annulation | Temporal, workflow racine | projection API PostgreSQL |
| Cycle de vie d'une invocation d'agent | `AgentTaskWorkflowV1` Temporal | tâche A2A persistée |
| Identité, capacités, skills et interfaces d'un agent | Agent Card signée dérivée du catalogue | cache borné de l'orchestrateur |
| Hiérarchie, autonomie et permissions | catalogue d'agents versionné | Agent Cards et règles d'autorisation générées |
| Association workflow/tentative/tâche A2A | table de corrélation de l'orchestrateur | attributs de recherche et traces |
| Messages A2A et états protocolaires | stockage de tâches A2A | historique borné et rétention explicite |
| Résultats métier | artefacts A2A validés | références compactes dans Temporal |
| Contenu des preuves | Evidence MCP | références digestées dans A2A et Temporal |
| Accès aux outils | matrice MCP et identité du rôle | claims d'identité et journal d'audit |
| Effets SCM | Gitea via SCM MCP | preuve et état projeté |

## 5. Périmètre des agents

Chaque rôle `kind: agent` ou `kind: sub-agent` du catalogue doit disposer d'une adresse A2A et d'une Agent Card.
Le rôle `workflow` reste le contrôle Temporal et ne devient pas un agent A2A.

- [ ] `supervisor`
- [ ] `architecture-agent`
- [ ] `impact-analysis`
- [ ] `dependencies-contracts`
- [ ] `code-agent`
- [ ] `developer`
- [ ] `patch-repair`
- [ ] `test-agent`
- [ ] `test-design`
- [ ] `test-evidence`
- [ ] `security-agent`
- [ ] `threat-model`
- [ ] `security-findings`
- [ ] `independent-reviewer`

## 6. Choix protocolaires cibles

- [x] **A2A-010 — Figer A2A 1.0.** Envoyer `A2A-Version: 1.0`, refuser le downgrade implicite vers `0.3` et
  tester `VersionNotSupportedError`. _(Version et règle de négociation sans downgrade fixées dans
  `ADR-A2A-001`; le test protocolaire sera livré avec le serveur au ticket A2A-060.)_
- [x] **A2A-011 — Qualifier le SDK Java.** Évaluer puis épingler une version finale du SDK officiel, avec
  `1.1.0.Final` comme version candidate, après vérification JDK 25, Spring Boot 4.1, Netty/Reactor, licences, SBOM
  et vulnérabilités. _(Version épinglée dans le POM ; résolution et arbre Maven, suite complète et scan Trivy
  validés. Matrice : `docs/qualification/a2a/A2A-JAVA-SDK-1.1.0.md`.)_
- [x] **A2A-012 — Choisir un binding unique pour la coupure.** Utiliser JSON-RPC 2.0 sur HTTPS comme interface
  A2A préférée initiale ; ne déclarer REST ou gRPC dans les Agent Cards qu'après tests d'équivalence. _(Binding
  initial fixé par `ADR-A2A-001`; le SDK qualifié fournit le transport JSON-RPC sans activer les autres.)_
- [x] **A2A-013 — Encapsuler le SDK.** Créer des ports applicatifs `A2aClient`, `A2aTaskServer`,
  `AgentCardResolver` et `A2aNotificationReceiver` pour isoler le domaine des classes du SDK. _(Ports asynchrones
  et contrats immuables ajoutés sous `com.example.aifactory.a2a`; un test de réflexion interdit toute fuite de
  types A2A SDK, Spring, Reactor ou Temporal dans leurs signatures.)_
- [x] **A2A-014 — Fixer le profil asynchrone.** Envoyer `SendMessage` avec `returnImmediately: true`, recevoir les
  transitions par notification push authentifiée et utiliser `GetTask` comme mécanisme de réconciliation.
  _(`SendCommand` rejette tout profil bloquant ; les ports dédiés imposent notification et réconciliation.)_
- [x] **A2A-015 — Réserver le streaming à l'observation interactive.** Ne pas conserver de flux SSE ouvert dans
  une activité Temporal ; `SendStreamingMessage` et `SubscribeToTask` restent désactivés tant qu'un besoin et des
  tests de reprise ne les justifient pas. _(Les ports n'exposent aucune opération de streaming et une carte qui
  tenterait de l'annoncer est rejetée par le contrat interne.)_
- [x] **A2A-016 — Définir les types de média.** Utiliser `application/json` pour les contrats métier structurés,
  `text/plain` uniquement pour les messages humains et des références Evidence pour les fichiers. _(Allow-list
  fermée dans `A2aMediaTypes`; `A2aContracts.Part` contrôle la cohérence entre media type et contenu.)_
- [x] **A2A-017 — Définir une extension de corrélation.** Versionner une extension A2A
  `https://ai-factory.local/extensions/execution-context/v1` portant uniquement les identifiants et digests
  nécessaires, sans secret ni contenu métier volumineux. _(Schéma Draft 2020-12 fermé et borné ajouté sous
  `resources/a2a/extensions`; tests positifs et négatifs sur rôles, digests et champs secrets.)_

### Critères de sortie des décisions protocolaires

- [ ] L'ADR fixe la version, le binding, l'authentification, la découverte, la rétention et le rollback.
- [ ] La matrice de compatibilité SDK/JDK/Spring/Temporal est reproductible.
- [ ] Aucun terme MCP ne désigne un appel A2A et inversement dans le code ou la documentation.

## 7. Lot 0 — inventaire et baseline avant migration

- [x] **A2A-020 — Cartographier tous les appels directs.** Identifier chaque appel d'un coordinateur ou agent à
  un autre agent, son contrat d'entrée/sortie, son timeout, ses erreurs et sa couverture de tests. _(Inventaire
  actif, latent et test-only, mappings A2A, timeouts, erreurs et points de suppression consignés dans
  `docs/qualification/a2a/A2A-DIRECT-CALL-INVENTORY.md`.)_
- [x] **A2A-021 — Geler une baseline fonctionnelle.** Capturer les verdicts, artefacts, ordres de délégation,
  consommations et chronologies des fixtures `short-path`, `multi-domain`, `adversarial` et `recovery`.
  _(Manifeste digesté de 36 cas, artefacts, chronologie et consommations ajouté sous `resources/a2a/baselines` ;
  preuve et commandes reproductibles dans `docs/evidence/a2a/A2A-021-FUNCTIONAL-BASELINE.md`, avec 43 tests verts.)_
- [x] **A2A-022 — Inventorier les états Temporal.** Associer chaque child workflow et activité d'agent actuel à
  la future interaction A2A sans modifier les règles métier. _(Workflows, activités enregistrées ou latentes,
  queues, états d'attente et correspondances `resolve/dispatch/get/cancel/validate` consignés dans
  `docs/qualification/a2a/A2A-TEMPORAL-STATE-INVENTORY.md`.)_
- [x] **A2A-023 — Inventorier les contrats.** Recenser les schémas `delegation-plan-v1`, `specialist-task-v1`,
  `supervisor-decision-v1`, les contrats par domaine et les références Evidence attendues. _(Les 18 contrats,
  producteurs/consommateurs, matrices de rôles, règles de preuve et quatre écarts de catalogue sont consignés dans
  `docs/qualification/a2a/A2A-BUSINESS-CONTRACT-INVENTORY.md` avec les digests des sources.)_
- [x] **A2A-024 — Inventorier les dépendances runtime.** Relever les accès LLM, MCP, base, filesystem, horloge,
  secrets et réseau nécessaires à chaque rôle. _(Matrice des quatorze rôles et frontières LLM, MCP, stockage,
  filesystem, horloge, secrets et egress consignées dans
  `docs/qualification/a2a/A2A-RUNTIME-DEPENDENCY-INVENTORY.md`.)_
- [x] **A2A-025 — Mesurer la baseline opérationnelle.** Capturer latence p50/p95/p99, taux d'échec, volume de
  payload, tokens, coût, concurrence et consommation mémoire par rôle. _(Manifeste recalculable depuis les 20
  résultats bruts ajouté sous `resources/a2a/baselines`; les mesures absentes de l'ancien monolithe sont marquées
  indisponibles, jamais zéro, dans `docs/evidence/a2a/A2A-025-OPERATIONAL-BASELINE.md`.)_
- [x] **A2A-026 — Créer le registre des risques.** Couvrir double exécution, perte de notification, divergence
  d'état A2A/Temporal, rejeu, usurpation de carte, SSRF, fuite de données et saturation. _(Vingt risques scorés,
  propriétaires, traitements, signaux, réponses et tickets de preuve consignés dans
  `docs/qualification/a2a/A2A-RISK-REGISTER.md`.)_

### Critères de sortie du lot 0

- [x] Chaque invocation actuelle possède une cible A2A et un propriétaire identifiés.
- [x] Les résultats de baseline sont stockés comme preuves immuables.
- [x] Aucun accès implicite requis par un agent n'est oublié dans la matrice de permissions.

## 8. Lot 1 — extraire un cœur d'agent indépendant

- [x] **A2A-030 — Créer un module partagé `agent-core`.** Extraire modèles, validateurs, catalogue, prompts,
  boucle d'outils et contrats sans dépendance à Spring Web, au SDK A2A ou au SDK Temporal. _(Module Maven autonome
  `apps/agent-core` ajouté au reactor racine avec catalogue, prompts, identité, moteur, boucle et validation des 18
  contrats ; Enforcer et test source interdisent Spring, Reactor, A2A et Temporal.)_
- [x] **A2A-031 — Créer l'application `a2a-agent-runtime`.** Construire une image générique paramétrée par un
  unique `agentRole`, avec profils strictement séparés du contrôle-plane de l'orchestrateur. _(Application Spring
  Boot dédiée, profil `agent-runtime`, dépendance sur `agent-core`, absence de datasource de contrôle et Dockerfile
  non-root générique ajoutés dans `apps/a2a-agent-runtime`.)_
- [x] **A2A-032 — Rendre le rôle obligatoire au démarrage.** Refuser une valeur absente, inconnue, `workflow` ou
  incompatible avec les ressources embarquées. _(`AgentManifest` compare identité, hiérarchie, outils, contrats et
  prompt au catalogue ; les 14 rôles passent, les valeurs absente/inconnue/`workflow` et le démarrage sans rôle
  échouent. L'écart `patch-repair` détecté en A2A-023 est corrigé.)_
- [x] **A2A-033 — Charger une seule identité d'agent.** Limiter prompts, skills, sorties et permissions à la
  définition du rôle actif ; empêcher l'activation dynamique d'un second rôle. _(`RoleScopedAgentContext` est
  l'unique frontière exposée au runtime : identité et prompt uniques, contrats d'entrée/sortie et outils filtrés ;
  le catalogue, le dépôt de prompts et le registre global ne sont plus des beans, et un second rôle est refusé.)_
- [x] **A2A-034 — Extraire les adaptateurs LLM.** Conserver le comportement, les budgets et les formats de réponse
  existants derrière un port sans couplage à l'orchestrateur. _(`LlmCompletionPort` et ses erreurs stables résident
  dans `agent-core` ; l'adaptateur OpenAI-compatible du runtime préserve messages, JSON final, outils, usages,
  coûts, classifications d'erreur, timeout et plafond historique de 8 192 tokens.)_
- [x] **A2A-035 — Extraire les clients MCP.** Construire pour chaque instance uniquement les connexions MCP
  nécessaires au rôle et appliquer la matrice `tools` du catalogue à chaque appel. _(`McpToolPort` et
  `RoleScopedMcpClient` filtrent définitions et serveurs par rôle, ouvrent les sessions SDK à la demande, négocient
  les outils et revérifient le droit avant chaque appel ; seuls Context et Evidence sont accessibles aux agents.)_
- [x] **A2A-036 — Retirer les effets interdits des agents.** Aucun runtime d'agent ne reçoit d'accès direct à SCM,
  au Docker daemon, à la base Temporal ou à la projection applicative. _(Enforcer interdit SDK SCM/Docker/Temporal,
  JDBC/JPA/PostgreSQL/Flyway ; configuration, code source et image sont contrôlés automatiquement, seuls MCP
  Context et les lectures Evidence restent joignables. Preuve : `docs/qualification/a2a/A2A-036-RUNTIME-EFFECT-ISOLATION.md`.)_
- [x] **A2A-037 — Ajouter des règles d'architecture.** Interdire par test les imports du contrôle-plane vers les
  implémentations d'agents et les imports des agents vers les contrôleurs, projections ou clients SCM. _(Les DTO
  sont déplacés dans le port `AgentExecutor`, Temporal n'importe plus `AgentRuntime`, et
  `AgentArchitectureRulesTest` contrôle les deux sens de dépendance. Preuve :
  `docs/qualification/a2a/A2A-037-ARCHITECTURE-RULES.md`.)_
- [x] **A2A-038 — Produire deux exécutables distincts.** L'orchestrateur embarque le client A2A et les workers de
  contrôle ; le runtime d'agent embarque le serveur A2A et les workers d'exécution d'un rôle. _(Le POM orchestrateur
  conserve uniquement le SDK client et Temporal ; le runtime embarque `server-common`, le transport JSON-RPC et
  `AgentExecutionWorker`, sans Temporal ni contrôle-plane. `ExecutableSeparationTest` verrouille les graphes.)_

### Critères de sortie du lot 1

- [x] Le cœur d'agent est testable sans réseau, Temporal ou Spring.
- [x] Démarrer un runtime avec un rôle ne charge aucun autre rôle.
- [ ] L'orchestrateur compile sans implémentation concrète d'agent dans son graphe Spring.

## 9. Lot 2 — définir la correspondance entre contrats métier et A2A

- [x] **A2A-040 — Créer `a2a-envelope-v1`.** Définir un schéma interne pour l'instruction structurée placée dans
  une `Part`, comprenant version, rôle cible, skill, références d'entrée, contraintes et budget. _(Schéma Draft
  2020-12 fermé et borné dans `resources/a2a/schemas`, fixture valide et cas négatifs automatisés.)_
- [x] **A2A-041 — Créer `a2a-artifact-reference-v1`.** Définir URI Evidence, SHA-256, taille, media type,
  classification, contrat métier et version. _(Schéma fermé avec URI `evidence://`, SHA-256, limite 10 MiB,
  media types, classification et versions explicites ; les références de l'enveloppe utilisent la même forme.)_
- [x] **A2A-042 — Mapper chaque contrat d'entrée.** Associer les contrats actuels à un `AgentSkill.id`, une version
  de schéma et une liste de types de média acceptés. _(Les 33 couples rôle/contrat d'entrée sont exhaustivement
  mappés dans `resources/a2a/skill-contract-map-v1.json` ; unicité, schémas existants et media types supportés sont
  vérifiés automatiquement.)_
- [x] **A2A-043 — Mapper chaque contrat de sortie.** Publier exactement un artefact final principal conforme au
  contrat du rôle, plus des références de preuves optionnelles et bornées. _(Les 15 sorties sont mappées, avec un
  unique contrat principal par rôle et `delegation-plan-v1` interne ; `a2a-result-v1` impose un seul artefact JSON
  principal et au plus 32 références Evidence conformes à A2A-041.)_
- [x] **A2A-044 — Conserver la validation actuelle.** Valider le JSON métier avant l'envoi A2A et après réception,
  indépendamment de la validation du modèle protocolaire A2A. _(`A2aBusinessContractGuard` vérifie mapping puis
  schéma métier et corrélation avant envoi et après réception ; un message protocolairement valide ne peut pas
  contourner les 18 schémas existants.)_
- [x] **A2A-045 — Définir la corrélation.** Transporter `taskId`, `attemptId`, `workflowId`, `workflowRunId`,
  `sourceCommit`, `repositoryId`, `delegationId`, `parentDelegationId`, `agentRole` et digests dans l'extension de
  corrélation. _(`A2aExecutionContext` sérialise et relit ces champs sous l'identifiant officiel, avec bornes,
  rôles fermés, SHA de commit complet et 1 à 32 digests SHA-256 uniques.)_
- [x] **A2A-046 — Respecter les identifiants A2A.** Laisser le serveur générer `Task.id` et `contextId`; persister
  leur association avec les identifiants métier et ne jamais utiliser l'ID A2A comme clé métier principale.
  _(V016 et `PostgresA2aTaskAssociationStore` indexent l'association par `delegation_id`, enregistrent les deux IDs
  retournés par le serveur et refusent tout replay divergent ; aucun ID A2A n'est clé métier.)_
- [x] **A2A-047 — Rendre `messageId` idempotent.** Dériver un UUID stable de l'identité d'exécution, du rôle, du
  skill, de la séquence et du digest d'entrée ; retourner la même tâche pour un message identique et rejeter une
  collision avec un payload différent. _(`A2aMessageIdentity` produit un UUID v8 déterministe par SHA-256 ; le
  registre atomique déduplique les replays et refuse une collision de digest sans créer une seconde tâche.)_
- [x] **A2A-048 — Borner les données.** Fixer limites de taille, nombre de parts, historique, artefacts, références,
  profondeur JSON et durée de conservation ; rejeter avant désérialisation complète les requêtes hors limite.
  _(`payload-limits-v1.json` et `A2aPayloadLimits` fixent 1 MiB, 16 parts, 50 historiques, 16 artefacts,
  32 références, profondeur 32 et rétention 30 jours ; la taille brute est refusée avant parsing.)_
- [x] **A2A-049 — Mapper les états.** Documenter et tester la correspondance entre états A2A et événements
  Temporal sans confondre panne technique et refus métier. _(`A2aTemporalStateMapper` et le mapping versionné
  couvrent les huit états supportés ; un rejet métier est non retryable tandis qu'un échec reste à classifier.)_

### Table de correspondance d'états à figer

| État A2A | Traitement Temporal attendu |
|---|---|
| `TASK_STATE_SUBMITTED` | association persistée, attente non bloquante |
| `TASK_STATE_WORKING` | heartbeat logique et métriques, aucune transition métier finale |
| `TASK_STATE_INPUT_REQUIRED` | demande structurée signalée au workflow ; gate ou complément contrôlé |
| `TASK_STATE_AUTH_REQUIRED` | suspension fail-closed et escalade ; aucun credential dans le message |
| `TASK_STATE_COMPLETED` | validation des artefacts puis reprise du DAG |
| `TASK_STATE_REJECTED` | rejet métier non retryable avec preuves |
| `TASK_STATE_FAILED` | classification technique/métier avant décision de retry Temporal |
| `TASK_STATE_CANCELED` | confirmation d'annulation idempotente |

### Critères de sortie du lot 2

- [x] Tous les schémas métier existants ont un mapping A2A bidirectionnel testé.
- [x] Les identifiants serveur A2A sont corrélés sans devenir des clés métier.
- [x] Un retry Temporal de `SendMessage` ne crée jamais une seconde exécution logique.

## 10. Lot 3 — Agent Cards, découverte et catalogue

- [x] **A2A-050 — Générer les Agent Cards depuis le catalogue.** Éviter toute duplication manuelle des rôles,
  skills, propriétaires, contrats, types de média et capacités. _(`AgentCardCatalogGenerator` joint au démarrage
  le catalogue canonique des 14 rôles et le mapping versionné des skills, et échoue si leurs périmètres divergent.)_
- [x] **A2A-051 — Publier une carte par rôle.** Exposer la carte à l'URI well-known du rôle et une carte étendue
  uniquement si un besoin authentifié est démontré. _(`AgentCardController` publie uniquement la carte du rôle
  actif à `/.well-known/agent-card.json`; aucune route de carte étendue n'est exposée.)_
- [x] **A2A-052 — Déclarer fidèlement les interfaces.** Publier URL interne, binding `JSONRPC`, version `1.0` et
  capacités réellement actives ; ne pas annoncer streaming ou push avant leur implémentation complète. _(La carte
  publie l'endpoint configuré, `preferredTransport`/interface `JSONRPC`, protocole `1.0` et les deux capacités à
  `false`, conformément au runtime effectivement disponible.)_
- [x] **A2A-053 — Déclarer la sécurité.** Décrire les schémas mTLS/OAuth2 réellement appliqués et les exigences
  d'autorisation par skill. _(Le profil sécurisé installe le resource server OAuth2 et refuse de démarrer sans
  HTTPS/mTLS `client-auth=need`; la carte annonce alors seulement ces mécanismes et génère les scopes de rôle,
  d'opération et de skill depuis le catalogue.)_
- [x] **A2A-054 — Signer les cartes.** Canonicaliser selon RFC 8785, signer en JWS, publier `kid` et chaîne de
  confiance, et permettre une rotation avec chevauchement de clés. _(`A2aAgentCardSigner` canonicalise via JCS,
  produit des JWS RS256 détachés et publie `kid`/JWK public/`x5c`; un JWK Set monté peut signer simultanément avec
  la clé active et les précédentes pendant la rotation, et le mode sécurisé exige une chaîne `x5c`.)_
- [x] **A2A-055 — Vérifier les cartes côté client.** Contrôler signature, issuer/provider, rôle attendu, URL,
  version, binding, skills, expiration et empreinte avant la première invocation. _(`A2aAgentCardVerifier`
  canonicalise la carte, exige une signature issue d'un `kid`/fingerprint allow-listé et contrôle tous les champs
  d'identité, d'interface, de capacité et de catalogue avant de produire le descripteur applicatif.)_
- [x] **A2A-056 — Créer un registre interne allow-listé.** Associer rôle à URL de carte attendue ; interdire la
  découverte Internet dynamique et les redirections vers une origine non autorisée. _(Le registre v1 ferme les
  14 rôles sur leurs origines HTTPS Compose/GKE ; `AllowListedAgentRegistry` refuse rôle inconnu, profil divergent,
  redirection et URI finale différente avant toute lecture de carte.)_
- [x] **A2A-057 — Gérer le cache.** Respecter ETag/cache headers, borner la durée, revalider après rotation et
  conserver la dernière carte valide uniquement pendant une indisponibilité courte explicitement configurée.
  _(`CachingAgentCardResolver` borne `max-age`, revalide par ETag, purge après rotation et limite le stale à la
  fenêtre d'indisponibilité configurée ; toute réponse atteignable mais invalide reste fail-closed.)_
- [x] **A2A-058 — Ajouter une gate de cohérence.** Faire échouer le démarrage si le catalogue, les cartes, les
  contrats, les URLs Compose/GKE ou les permissions divergent. _(`A2aCatalogCoherenceGate` recoupe au démarrage
  les 14 rôles, manifests, contrats entrants/sortants, skills, permissions, délégations et origines des deux
  profils ; toute différence lève une erreur d'admission.)_

### Critères de sortie du lot 3

- [x] Les quatorze rôles possèdent une carte signée et vérifiable.
- [x] Un rôle ne peut annoncer ni invoquer un skill absent du catalogue.
- [x] Une carte altérée, expirée ou servie depuis une mauvaise origine bloque l'admission.

## 11. Lot 4 — construire le serveur A2A durable de chaque agent

- [x] **A2A-060 — Implémenter `SendMessage`.** Authentifier, autoriser, valider version et extension, dédupliquer
  `messageId`, créer la tâche et retourner immédiatement son état. _(`A2aJsonRpcController` expose uniquement
  `message/send` A2A 1.0 ; `A2aSendMessageService` contrôle identité, scopes rôle/skill, extension de corrélation,
  bornes et idempotence avant de retourner immédiatement une tâche `SUBMITTED` générée côté serveur.)_
- [x] **A2A-061 — Implémenter `GetTask`.** Retourner uniquement une tâche visible par l'appelant avec historique
  borné et artefacts autorisés. _(`tasks/get` exige les scopes de lecture/rôle avant lookup observable, masque
  absence et refus sous `TaskNotFound`, impose le même propriétaire, borne l'historique à 50 et ne retourne que
  la projection d'artefacts déjà autorisée.)_
- [x] **A2A-062 — Implémenter `ListTasks`.** Ajouter filtres, pagination par curseur, isolation par tenant et limite
  maximale ; ne jamais permettre l'énumération inter-tenant. _(`tasks/list` filtre état/contexte dans le périmètre
  tenant+appelant, limite les pages à 100 et lie chaque curseur opaque, à usage unique, à l'identité et aux filtres
  d'origine ; un token étranger est refusé.)_
- [x] **A2A-063 — Implémenter `CancelTask`.** Signaler l'annulation au workflow d'agent, rendre l'opération
  idempotente et refuser proprement un état terminal non annulable. _(`tasks/cancel` contrôle propriétaire et
  scopes avant lookup observable, signale `AgentTaskWorkflowControl` une seule fois, projette `CANCELED` de façon
  atomique et renvoie `TaskNotCancelableError` pour tout autre état terminal.)_
- [x] **A2A-064 — Implémenter les notifications push.** Autoriser seulement le callback fixe de
  l'orchestrateur, authentifier chaque notification, borner retries/backoff et journaliser les accusés sans secret.
  _(`A2aPushNotificationSender` signe en HMAC-SHA256 vers l'unique callback HTTPS configuré, borne tentatives et
  backoff, et ne journalise que tâche/tentative/statut ; la capacité de carte suit exactement son activation.)_
- [x] **A2A-065 — Créer un stockage durable des tâches.** Persister tâche, messages retenus, transitions,
  artefacts, ACL, digests, message idempotent et version avec verrouillage optimiste. _(Le schéma Flyway dédié et
  `PostgresA2aTaskStore` persistent projection, identité idempotente unique, historique, artefacts digestés/ACL et
  version CAS ; le service utilise exclusivement ce port et le fallback mémoire reste limité au profil désactivé.)_
- [x] **A2A-066 — Créer `AgentTaskWorkflowV1`.** Une tâche A2A démarre un workflow Temporal déterministe dédié,
  sur une task queue liée au rôle et à une version de worker épinglée. _(`AgentTaskWorkflowV1` est déterministe et
  `PINNED`; une insertion A2A nouvelle démarre idempotemment `a2a-agent-task-v1/<rôle>/<task>` sur
  `a2a-agent-<rôle>-v1`, dont le worker porte le deployment/build ID configuré.)_
- [x] **A2A-067 — Projeter l'état du workflow.** Mettre à jour la tâche A2A via des activités idempotentes ; ne
  jamais lire les tables internes de Temporal depuis le serveur A2A. _(`AgentTaskProjectionActivities` projette
  `WORKING` et l'état terminal par CAS sur `A2aTaskStore`; un retry déjà appliqué est un no-op et l'activité ne
  dépend d'aucune table ou API interne de stockage Temporal.)_
- [x] **A2A-068 — Reprendre après redémarrage.** Réconcilier tâches non terminales, workflow IDs et notifications
  non acquittées sans relancer le LLM ni les outils déjà confirmés. _(L'enveloppe et la corrélation Temporal sont
  durables ; `A2aRecoveryCoordinator` réutilise l'ID déterministe avec `USE_EXISTING` et draine un outbox de
  notifications idempotent, dont l'acquittement est persisté.)_
- [x] **A2A-069 — Publier les artefacts finaux.** Valider le contrat métier, stocker le contenu dans Evidence MCP,
  publier la référence A2A puis passer à `COMPLETED` dans une séquence réconciliable. _(L'activité idempotente
  `AgentArtifactActivities` valide contrat et digest, appelle `evidence.store`, persiste exclusivement la référence
  A2A liée au tenant, puis autorise la projection terminale du workflow.)_
- [x] **A2A-070 — Classer les erreurs.** Mapper erreurs de contrat, auth, quota, dépendance, timeout et métier vers
  les erreurs A2A et états appropriés, avec `google.rpc.Status/ErrorInfo` dans les détails. _(La frontière JSON-RPC
  classe ces six familles avec code A2A, statut gRPC canonique, `ErrorInfo` stable, retryabilité et état terminal ;
  les causes internes ne sont jamais renvoyées au client.)_
- [x] **A2A-071 — Ajouter readiness et liveness.** La readiness exige carte valide, stockage, Temporal, task queue,
  LLM et MCP obligatoires pour le rôle ; la liveness ne dépend pas des services aval. _(`agentRuntimeDependencies`
  agrège six contrôles stricts et sans secret ; le groupe liveness reste limité à `livenessState` et ne sonde aucun
  service aval.)_
- [x] **A2A-072 — Borner la concurrence.** Configurer file, pollers, exécutions par rôle, quotas par tenant,
  backpressure et graceful shutdown. _(`AgentConcurrencyProperties` borne pollers/exécutions, admission tenant et
  file durable ; les replays contournent correctement le quota, et l'arrêt suspend le polling avant d'attendre le
  délai gracieux configuré.)_

### Critères de sortie du lot 4

- [ ] Une tâche acceptée survit au redémarrage du serveur et du worker.
- [ ] La déduplication de `messageId` résiste à deux requêtes concurrentes.
- [ ] Une tâche n'est visible, annulable ou suivable que par son tenant et son client autorisé.
- [ ] La suite de conformité A2A passe pour toutes les opérations déclarées dans la carte.

## 12. Lot 5 — raccorder Temporal au client A2A

- [x] **A2A-080 — Créer les activités A2A.** Séparer `resolveAgent`, `dispatchTask`, `getTask`, `cancelTask` et
  `validateArtifacts` avec timeouts et retries spécifiques. _(`A2aActivities` expose cinq interfaces Temporal
  distinctes ; chaque stub utilise sa politique dédiée, et l'envoi n'est jamais rejoué automatiquement afin de
  laisser les résultats ambigus au mécanisme de réconciliation.)_
- [x] **A2A-081 — Persister la corrélation avant attente.** Enregistrer workflow, tentative, délégation,
  `messageId`, rôle, Agent Card digest, A2A task ID et context ID dans une table dédiée. _(La migration V017
  complète `a2a_task_associations`; `dispatchTask` persiste tous les identifiants et digests de façon idempotente
  avant de rendre la tâche au workflow appelant.)_
- [x] **A2A-082 — Réconcilier un résultat ambigu.** Après timeout de `SendMessage`, rechercher par `messageId` ou
  corrélation serveur avant tout nouvel envoi ; ne jamais supposer que la requête n'a pas été traitée.
  _(`reconcileDispatch` consulte d'abord l'association durable, puis le serveur par `messageId`; il ne réémet que
  la commande originale avec la même identité lorsque ces deux recherches prouvent l'absence.)_
- [x] **A2A-083 — Recevoir les notifications.** Exposer un endpoint interne authentifié, valider tâche, contexte,
  transition, séquence et digest, puis émettre un signal Temporal idempotent. _(`/internal/a2a/notifications`
  vérifie le HMAC du corps brut ; l'association et l'inbox V018 contrôlent corrélation, ordre et digest avant le
  signal `a2aTaskUpdate`, et un rejeu exact déjà signalé devient un no-op.)_
- [x] **A2A-084 — Attendre sans bloquer.** Utiliser `Workflow.await` et un timer de réconciliation ; aucun
  `block()`, `blockFirst()` ou `blockLast()` ne doit être exécuté sur un thread Reactor. _(`A2aTaskAwaiter` tamponne
  les signaux ordonnés et utilise uniquement `Workflow.await(interval, condition)` ; le callback WebFlux compose
  son `CompletionStage` sans blocage.)_
- [x] **A2A-085 — Gérer les notifications perdues.** À expiration du timer, appeler `GetTask`, appliquer les
  transitions manquantes dans l'ordre puis réarmer une attente bornée. _(`awaitUntilTerminal` interroge `GetTask`
  au timeout, refuse tout trou de séquence, rejoue les transitions ordonnées puis réarme le même intervalle tant
  que la tâche n'est pas terminale ; le serveur expose sa version de projection.)_
- [x] **A2A-086 — Propager l'annulation.** Envoyer `CancelTask`, attendre une confirmation bornée et préserver les
  preuves ; si l'agent est injoignable, conserver un état de réconciliation explicite.
  _(`A2aCancellationCoordinator` borne l'appel et trois cycles de confirmation signal/poll, conserve toutes les
  URI Evidence et retourne explicitement `RECONCILIATION_REQUIRED` lorsque l'agent reste injoignable.)_
- [x] **A2A-087 — Reprendre les demandes de complément.** Mapper `INPUT_REQUIRED` vers un signal ou une gate
  métier autorisée, puis envoyer un nouveau message sur le même `taskId/contextId`. _(Une décision autorisée et
  référencée dans Evidence produit un `messageId` déterministe ; l'activité contrôle l'association durable et le
  runtime accepte atomiquement le message uniquement sur la tâche `INPUT_REQUIRED`, le déduplique puis signale le
  workflow Temporal existant sans changer `taskId/contextId`.)_
- [x] **A2A-088 — Gérer `AUTH_REQUIRED`.** Ne jamais transmettre de secret dans l'historique ; obtenir la décision
  ou le jeton hors bande, avec portée liée à l'opération, puis reprendre par un message contrôlé. _(Temporal ne
  sérialise qu'un grant non secret lié au rôle, à l'opération, à la tâche, au contexte, à l'expiration et à un
  digest ; l'activité consomme le jeton hors bande, l'efface après l'appel, et le runtime exige le scope
  `a2a.auth-resume` tout en refusant tout credential dans le message ou son historique.)_
- [x] **A2A-089 — Remplacer les child workflows d'agents.** Conserver les child workflows de contrôle nécessaires
  au DAG, mais remplacer leur exécution Java directe par les activités A2A. _(Le registre de production utilise
  désormais `A2aDelegationWorkflowImpl` : le child vérifie la carte et le skill, construit une corrélation compacte,
  appelle `dispatchTask`, attend signal/réconciliation, puis valide l'artefact final ; l'ancienne implémentation
  reste uniquement comme type de compatibilité pour les historiques et tests antérieurs.)_
- [x] **A2A-090 — Maintenir la revue indépendante.** Adresser `independent-reviewer` par A2A avec les mêmes digests
  et sans accès aux raisonnements privés ou sorties non validées des autres agents. _(Le child de revue de
  production est désormais dédié à `independent-reviewer` et ne transmet que les références Evidence du patch
  consolidé, du manifeste, des résultats validés et des contradictions ; un test Temporal vérifie les digests et
  l'absence de prompt, raisonnement ou sortie brute.)_
- [x] **A2A-091 — Versionner le déterminisme.** Introduire un nouveau type de workflow ou Worker Versioning pour
  la frontière A2A ; ne pas modifier de manière incompatible les historiques Temporal en cours. _(Les workflows
  A2A sont `PINNED` sur un build immuable ; le nouveau build enregistre exclusivement les implémentations A2A,
  tandis que les anciens pollers drainent leurs historiques sans les rejouer avec le nouveau code. Procédure et
  preuves : `docs/qualification/a2a/A2A-091-WORKER-VERSIONING.md`.)_
- [x] **A2A-092 — Borner les historiques.** Ne stocker dans Temporal que IDs, états, digests et références ;
  déclencher `continue-as-new` selon les seuils existants. _(Les child workflows A2A contrôlent 250 événements,
  1 MiB et la recommandation serveur avant chaque attente ; un nouveau run reprend par `reconcileDispatch` avec
  la même identité logique et ne transporte que la requête compacte, les états et références digestées.)_

### Critères de sortie du lot 5

- [ ] Le DAG complet s'exécute avec Temporal comme seul ordonnanceur et A2A comme seule frontière d'agent.
- [ ] Perdre une notification ou redémarrer un composant ne perd pas le résultat.
- [ ] Un timeout ambigu, un retry ou un replay ne crée aucune seconde exécution logique.
- [ ] L'annulation racine se propage à toutes les tâches A2A non terminales.

## 13. Lot 6 — sécurité et isolation

- [x] **A2A-100 — Définir les identités de service.** Attribuer une identité distincte à l'orchestrateur et à
  chaque rôle d'agent ; interdire les credentials partagés entre rôles.
  - Preuve : `resources/a2a/service-identities-v1.json`, son schéma et
    `A2aServiceIdentityRegistryTest` lient l'orchestrateur et les 14 rôles à des clients OAuth, sujets SPIFFE et
    références de secrets tous distincts, et rejettent explicitement toute réutilisation inter-rôles.
- [x] **A2A-101 — Chiffrer tous les échanges.** Utiliser mTLS entre workloads en production et une PKI locale de
  développement dans Compose ; vérifier SAN, chaîne, expiration et révocation.
  - Preuve : `resources/a2a/tls-policy-v1.json`, `scripts/generate-a2a-local-pki.sh` et
    `scripts/verify-a2a-pki.sh` imposent TLS 1.3, certificats client obligatoires, identité SPIFFE, chaîne locale,
    durée minimale et CRL ; `scripts/test-a2a-pki.sh` prouve notamment le rejet d'un certificat révoqué.
- [x] **A2A-102 — Ajouter OAuth2 client credentials.** Utiliser des jetons courts avec audiences A2A et scopes par
  opération/skill ; ne pas placer les jetons dans les Agent Cards, payloads, logs ou historiques Temporal.
  - Preuve : `A2aClientCredentialsTokenProvider` lit le secret monté uniquement côté worker et borne le TTL,
    `A2aScopedOAuth2Client` demande les scopes minimaux puis efface le bearer token, et
    `A2aSecurityDeclarationTest` vérifie audience, durée et déclaration de scopes rôle/skill.
- [x] **A2A-103 — Appliquer l'autorisation avant lookup.** Vérifier tenant, client, rôle et skill avant toute
  requête susceptible de révéler l'existence d'une tâche.
  - Preuve : `A2aSendMessageService.requireLookupAuthorization` précède chaque lecture de tâche et
    `A2aAuthorizationOrderingTest` vérifie qu'un client au mauvais rôle provoque zéro interaction avec le store ;
    l'identifiant client et le tenant sont désormais obligatoirement extraits des claims JWT.
- [x] **A2A-104 — Isoler les permissions MCP.** Émettre des identités et tokens MCP par rôle, appliquer
  `mayDelegateTo` et `tools` côté serveur, et tester les refus.
  - Preuve : `mcp-role-token-policy-v1.json`, `McpRoleTokenProvider` et `RoleScopedMcpClient` lient identité,
    token, acteur, tools et délégations au rôle actif ; les filtres `McpToolAuthorizationFilter` de Repository
    Context et Evidence vérifient `client_id`, rôle et scope avant dispatch, avec tests négatifs dédiés.
- [x] **A2A-105 — Protéger les URLs.** Allow-lister Agent Cards, endpoints, callbacks et références Evidence ;
  interdire redirections, loopback, link-local, metadata cloud et résolutions DNS changeantes non autorisées.
  - Preuve : `SecureUriPolicy` impose HTTPS, destination exacte, filtrage réseau et DNS pinning pour le registre et
    les callbacks ; `A2aEvidenceUriPolicy`/`EvidenceUriPolicy` lient chaque référence au task/attempt/digest, avec
    tests négatifs de redirection, loopback, metadata cloud et rebinding.
- [x] **A2A-106 — Protéger les entrées non fiables.** Marquer les contenus de dépôt et messages comme données,
  appliquer les garde-fous d'injection existants et valider toutes les sorties avant utilisation.
  - Preuve : `UntrustedData` et `AgentLoop.INPUT_DATA_GUARDRAIL` encadrent toute entrée modèle et neutralisent les
    fermetures de balise injectées ; `AgentExecutionWorkerTest` prouve validation du contrat avant LLM et rejet de
    toute sortie non conforme avant publication.
- [x] **A2A-107 — Ajouter quotas et rate limiting.** Borner requêtes par identité, tâches actives, taille, tokens,
  fréquence de polling, annulations et notifications.
  - Preuve : `A2aIdentityRateLimiter` applique des fenêtres bornées par identité et classe d'opération aux envois,
    lectures, listes, annulations et tentatives de notification ; les quotas de tâches par tenant, la limite JSON-RPC
    de 1 Mio et les budgets tours/tokens/coût restent imposés par l'admission et le worker. Les tests du limiter, de
    l'admission, des opérations A2A, des notifications, du contrôleur et du worker couvrent les refus fail-fast.
- [x] **A2A-108 — Journaliser les décisions.** Tracer authentifications échouées, refus, changement de carte,
  délégations, annulations et collisions sans données sensibles.
  - Preuve : les journaux A2A agent et orchestrateur émettent un schéma fixe `a2a_decision` pour authentification,
    refus, délégation, annulation, collision, changement et invalidation de carte. Toutes les valeurs fournies sont
    remplacées par leur SHA-256 avant émission ; les tests vérifient le routage des événements et l'absence des
    identifiants, jetons, retours à la ligne et ETag d'origine.
- [x] **A2A-109 — Gérer les secrets.** Monter les secrets en fichiers, vérifier permissions, rotation et absence
  dans l'environnement sérialisé, les dumps, traces, tâches A2A et historiques Temporal.
  - Preuve : `secret-policy-v1.json` inventorie les secrets, leurs seuls chemins de montage, leurs rotations et les
    sinks interdits. `SecretFilePolicy` et `A2aSecretFilePolicy` refusent liens, permissions non propriétaires et
    tailles anormales ; OAuth2, MCP et HMAC effacent leurs buffers et relisent le fichier à l'usage. Les scripts
    génèrent 14 jeux isolés en mode `0600`, vérifient PKI/JWK et testent remplacement atomique, rotation, révocation
    et refus des permissions faibles. Les tests de transport prouvent aussi l'absence de secret dans erreurs,
    décisions, métadonnées A2A et historiques.
- [x] **A2A-110 — Produire l'analyse de menaces.** Couvrir spoofing de carte, confused deputy, rejeu, SSRF,
  élévation de privilège, poisoning d'artefact, cross-tenant et déni de service.
  - Preuve : `docs/architecture/a2a/A2A-110-threat-model.md` décrit actifs, adversaires, neuf frontières, STRIDE,
    cotation, huit scénarios, abus composés, contrôles, détection, réponse, tests et gouvernance ; le vérificateur
    relie automatiquement chaque scénario à ses preuves et aux vingt risques du registre A2A.
- [x] **A2A-111 — Ajouter les scans de supply chain.** SBOM, licences, signatures, provenance, Trivy et politique
  de vulnérabilités pour le SDK A2A et l'image runtime.
  - Preuve : `supply-chain-policy-v1.json` épingle le SDK, les licences, deux formats SBOM, les bases par digest,
    Trivy, Cosign keyless et une provenance SLSA. `qualify-a2a-runtime-image.sh` bloque toute référence mutable et
    produit les sept preuves avec manifeste SHA-256 ; les fixtures négatives refusent licence GPL et dérive SDK.
    La construction JDK 25 de l'image non-root passe les 63 tests et est consignée dans
    `docs/qualification/a2a/A2A-111-SUPPLY-CHAIN.md`.

### Critères de sortie du lot 6

- [ ] Les tests prouvent qu'un agent ne peut utiliser ni skill, ni outil, ni tâche d'un autre périmètre.
- [ ] Aucun secret ou contenu sensible n'est présent dans les traces, logs, erreurs ou historiques.
- [ ] La rotation des certificats, clés de carte et jetons fonctionne sans interrompre les tâches actives.

## 14. Lot 7 — Docker Compose macOS et déploiement GKE

- [x] **A2A-120 — Ajouter le runtime générique à Compose.** Construire une seule image et instancier un service
  explicitement nommé par rôle, sans `container_name`.
  - Preuve : `infrastructure/a2a/compose-agents.yaml` instancie les quatorze rôles explicites à partir de l'unique
    image `ai-factory-a2a-agent-runtime`, avec identité et endpoint propres mais sans `container_name` ;
    `verify-a2a-compose-runtime.rb` contrôle exhaustivité, unicité d'image, UID et cohérence rôle/service.
- [x] **A2A-121 — Isoler le réseau A2A.** Créer un réseau interne dédié reliant orchestrateur et agents ; ne publier
  aucun endpoint A2A sur l'hôte par défaut.
  - Preuve : le réseau Compose `ai-factory-a2a-internal` est déclaré `internal` et ne compte que l'orchestrateur et
    les quatorze runtimes ; aucun service agent ne déclare de port hôte. `verify-a2a-compose-network.rb` contrôle
    ces invariants sur le modèle Compose entièrement résolu.
- [ ] **A2A-122 — Conserver les réseaux MCP minimaux.** Chaque agent rejoint seulement les réseaux des MCP qu'il
  peut utiliser ; l'orchestrateur conserve les capacités à effet qui lui appartiennent.
- [ ] **A2A-123 — Ajouter le stockage local durable.** Fournir la base/projection de tâches A2A et ses migrations,
  healthchecks et volumes nommés compatibles Docker Desktop.
- [ ] **A2A-124 — Générer les certificats locaux.** Fournir une cible d'initialisation idempotente, des fichiers
  hors Git, une rotation simple et des valeurs `.env.example` sans secret réel.
- [ ] **A2A-125 — Aligner `.env` et `.env.example`.** Regrouper URLs, version, timeouts, limites, certificats,
  cache de cartes et notifications dans le même ordre.
- [ ] **A2A-126 — Ajouter les cibles Make.** Fournir `a2a-config`, `a2a-status`, `a2a-cards`, `a2a-smoke`,
  `a2a-logs` et `a2a-reset-state` avec garde explicite sur la suppression locale.
- [ ] **A2A-127 — Ajouter des profils de test.** Permettre de lancer une topologie minimale pour un rôle et la
  topologie complète pour l'E2E, sans modifier le chemin de production.
- [ ] **A2A-128 — Dimensionner macOS.** Documenter CPU, mémoire, disque, temps de démarrage et réglages Docker
  Desktop ; regrouper uniquement les processus si l'isolation de rôle et les permissions restent prouvées.
- [ ] **A2A-129 — Préparer les manifests GKE.** Déployer un workload/service par rôle, NetworkPolicies,
  PodDisruptionBudgets, probes, autoscaling par files et secrets via le mécanisme de plateforme.
- [ ] **A2A-130 — Ajouter la découverte GKE.** Utiliser des DNS de service stables dans le registre allow-listé ;
  ne pas dépendre des IP de pods ni d'un registre public.
- [ ] **A2A-131 — Protéger l'entrée.** Garder les endpoints A2A privés au cluster ; si une exposition externe est
  ultérieurement requise, la traiter dans une ADR et une threat model dédiées.

### Critères de sortie du lot 7

- [ ] `make all` démarre la topologie A2A complète sur macOS et termine ses smoke tests.
- [ ] Les quatorze Agent Cards sont récupérables depuis l'orchestrateur mais pas depuis l'hôte par défaut.
- [ ] Une instance peut redémarrer sans perte de tâche ni création de doublon.
- [ ] Les règles réseau GKE reproduisent les frontières de capacité validées sous Compose.

## 15. Lot 8 — observabilité OpenTelemetry et exploitation

- [ ] **A2A-140 — Propager le contexte W3C.** Transmettre `traceparent` et `baggage` validés à travers API,
  Temporal, activités A2A, serveur d'agent, workflow d'agent et MCP.
- [ ] **A2A-141 — Corréler sans cardinalité excessive.** Placer task/workflow/message IDs dans traces et logs,
  jamais comme labels de métriques ; utiliser rôle, skill, opération, version et état comme dimensions bornées.
- [ ] **A2A-142 — Instrumenter le client.** Mesurer durée, résultat, retry, timeout, réconciliation, validation de
  carte, taille de payload et âge de notification.
- [ ] **A2A-143 — Instrumenter le serveur.** Mesurer admission, refus auth, déduplication, durée de tâche,
  transitions, tâches actives, backlog, polling et notifications.
- [ ] **A2A-144 — Tracer les liens Temporal/A2A.** Ajouter des span links entre workflow racine, activité d'envoi,
  tâche A2A et workflow d'agent sans produire de doublons pendant un replay.
- [ ] **A2A-145 — Créer les dashboards SigNoz.** Vue flotte d'agents, latence par rôle/skill, états des tâches,
  erreurs protocolaires, divergence Temporal/A2A, retries, files et saturation.
- [ ] **A2A-146 — Créer les alertes.** Détecter absence de poller, agent non prêt, carte invalide, taux d'échec,
  backlog, tâche bloquée, notification en retard, collision d'idempotence et divergence d'état.
- [ ] **A2A-147 — Ajouter des SLO.** Définir disponibilité de dispatch, délai de prise en charge, délai de
  terminaison, taux de doublon nul et délai de propagation d'annulation.
- [ ] **A2A-148 — Ajouter des runbooks.** Documenter carte invalide, agent indisponible, task stuck, callback
  perdu, divergence, certificat expiré, saturation et rollback.
- [ ] **A2A-149 — Rendre la readiness globale explicable.** Exposer quels rôles, cartes, task queues ou
  dépendances empêchent les nouvelles admissions.

### Critères de sortie du lot 8

- [ ] Une exécution est navigable de l'API à l'artefact Evidence en passant par Temporal et A2A.
- [ ] Les dashboards n'utilisent aucun label non borné.
- [ ] Chaque alerte possède un seuil, une durée, un propriétaire et un runbook testé.

## 16. Lot 9 — stratégie de tests et qualification

- [ ] **A2A-160 — Tests unitaires.** Couvrir mapping, limites, états, erreurs, ACL, idempotence, cache de cartes,
  signature, corrélation et redaction.
- [ ] **A2A-161 — Tests de contrats.** Valider toutes les Agent Cards, enveloppes, artefacts et extensions contre
  leurs schémas et fixtures dorées.
- [ ] **A2A-162 — Exécuter le TCK A2A officiel.** Tester chaque opération et capacité déclarée avec la version
  épinglée du protocole et archiver le rapport.
- [ ] **A2A-163 — Tester l'interopérabilité.** Appeler au moins un serveur de référence depuis le client du projet
  et le serveur du projet depuis un client officiel indépendant.
- [ ] **A2A-164 — Tests Temporal embarqués.** Vérifier attentes, signaux, timers, retries, annulation, timeouts
  ambigus, `continue-as-new` et replay.
- [ ] **A2A-165 — Tests d'intégration Compose.** Couvrir cartes, envoi, résultat, complément, annulation,
  redémarrage, perte de callback et réconciliation.
- [ ] **A2A-166 — Tests de panne.** Couper réseau, agent, base, Temporal, LLM, MCP et Evidence aux différents
  instants ; vérifier absence de doublon et reprise contrôlée.
- [ ] **A2A-167 — Tests de concurrence.** Envoyer simultanément le même `messageId`, annuler pendant la
  terminaison, faire tourner les clés et redémarrer les workers.
- [ ] **A2A-168 — Tests de sécurité.** Fuzzing JSON-RPC, payloads malformés, cartes forgées, jetons croisés,
  cross-tenant, SSRF, rejeu, redirections, dépassement de taille et injection.
- [ ] **A2A-169 — Tests de performance.** Comparer à la baseline latence, débit, mémoire et coût ; valider
  backpressure et comportement à saturation.
- [ ] **A2A-170 — Tests E2E métier.** Rejouer toutes les fixtures multi-agents et comparer verdicts, digests,
  gates, ordre de délégation et usage aux preuves de référence.
- [ ] **A2A-171 — Tests de non-régression pipeline.** Vérifier que le mode `PIPELINE`, s'il reste supporté comme
  mode métier, ne réintroduit aucun appel direct d'agent ni sélection de transport.
- [ ] **A2A-172 — Audit de dépendances.** Exécuter tests, analyse statique, scan de vulnérabilités, SBOM et
  vérification de licences sur tous les modules et images.

### Seuils de qualification à fixer dans la gate

- [ ] Zéro échec du TCK pour les capacités annoncées.
- [ ] Zéro doublon logique ou effet externe dupliqué sur les campagnes de panne et de concurrence.
- [ ] Zéro accès inter-tenant ou hors matrice de permissions.
- [ ] Zéro erreur de replay Temporal sur les historiques de référence.
- [ ] Parité fonctionnelle complète sur les fixtures existantes.
- [ ] Régression p95 et consommation bornées par des seuils approuvés avant la coupure.
- [ ] Tous les scénarios d'annulation et de reprise disposent de preuves exploitables.

## 17. Lot 10 — préparation et bascule franche

- [ ] **A2A-180 — Créer la gate de cutover.** Rassembler version, commit, images, digests, SBOM, TCK, E2E,
  sécurité, performance, replay, rollback et approbateurs.
- [ ] **A2A-181 — Geler les admissions.** Suspendre les nouveaux tickets avant le déploiement de coupure et laisser
  terminer ou annuler proprement les exécutions incompatibles.
- [ ] **A2A-182 — Sauvegarder les états.** Sauvegarder projections, corrélations, preuves de qualification,
  configuration signée et versions de workers sans accéder aux tables internes Temporal.
- [ ] **A2A-183 — Déployer les agents A2A.** Démarrer stockage, runtimes, Agent Cards, workers et notifications,
  puis vérifier toutes les readyness et task queues.
- [ ] **A2A-184 — Activer la version Temporal A2A.** Déployer le nouveau type/build de workflow et vérifier la
  compatibilité des workers avant d'autoriser les admissions.
- [ ] **A2A-185 — Couper les appels directs en une fois.** Livrer la suppression des beans/routes directes dans le
  même release que l'activation A2A ; ne conserver aucun feature flag de fallback.
- [ ] **A2A-186 — Exécuter le smoke test de production.** Vérifier Supervisor, un chemin hiérarchique complet,
  revue indépendante, gate humaine, annulation et récupération des preuves.
- [ ] **A2A-187 — Rouvrir les admissions.** Autoriser les nouveaux tickets uniquement lorsque toutes les cartes,
  files Temporal, runtimes et dépendances obligatoires sont prêtes.
- [ ] **A2A-188 — Surveiller la fenêtre de stabilisation.** Contrôler SLO, backlog, divergences, notifications,
  coûts, erreurs et saturation pendant la durée approuvée.
- [ ] **A2A-189 — Faire approuver la bascule.** Obtenir une décision humaine explicite liée au commit, aux images,
  rapports et preuves exactes.

### Critères de sortie du lot 10

- [ ] Toutes les nouvelles invocations d'agents passent exclusivement par A2A 1.0.
- [ ] Temporal demeure l'unique moteur de coordination et aucun agent ne délègue hors de son contrôle.
- [ ] La recherche automatisée ne trouve aucun chemin d'exécution direct résiduel.
- [ ] Le smoke test et la fenêtre de stabilisation sont approuvés avec leurs digests.

## 18. Lot 11 — rollback sans retour aux appels directs

- [ ] **A2A-200 — Définir le rollback de release.** Revenir aux images A2A et workers Temporal précédents,
  compatibles avec les tâches déjà créées ; ne jamais réactiver les agents en mémoire.
- [ ] **A2A-201 — Définir le drainage.** Conserver les workers associés aux anciens Build IDs jusqu'à terminaison
  ou migration explicite des workflows épinglés.
- [ ] **A2A-202 — Geler plutôt que contourner.** Si la flotte A2A est indisponible, suspendre les admissions et
  laisser Temporal attendre/réconcilier au lieu d'exécuter localement.
- [ ] **A2A-203 — Réconcilier avant retry.** Examiner la tâche A2A, son workflow d'agent et ses artefacts avant
  toute relance après incident.
- [ ] **A2A-204 — Tester le rollback sous charge.** Couvrir tâches `SUBMITTED`, `WORKING`, `INPUT_REQUIRED`,
  `COMPLETED` non notifiées et annulation en cours.
- [ ] **A2A-205 — Documenter les limites irréversibles.** Identifier migrations de données expand/contract,
  versions de cartes et contrats qui empêchent un retour binaire sans transformation.
- [ ] **A2A-206 — Exiger une gate de rollback.** Lier décision, incident, versions, état des tâches, sauvegardes et
  résultat de réconciliation à une approbation opérateur.

### Critères de sortie du lot 11

- [ ] Le rollback testé ne perd ni tâche, ni message requis, ni artefact, ni annulation.
- [ ] Aucun scénario de rollback ne réintroduit un appel Java direct entre agents.
- [ ] Les anciennes et nouvelles versions de workers peuvent drainer leurs historiques respectifs.

## 19. Lot 12 — nettoyage et documentation finale

- [ ] **A2A-220 — Supprimer le code direct obsolète.** Retirer adaptateurs, beans, propriétés, tests et imports
  devenus inutiles, après preuve qu'ils ne servent plus de chemin de production.
- [ ] **A2A-221 — Supprimer les flags temporaires de qualification.** Aucun sélecteur `DIRECT/A2A`, shadow ou
  fallback ne subsiste dans la configuration de release.
- [ ] **A2A-222 — Mettre à jour les schémas d'architecture.** Montrer services d'agents, réseau A2A, Temporal,
  MCP, Evidence et frontières de confiance.
- [ ] **A2A-223 — Mettre à jour le README.** Expliquer que les agents sont désormais adressables par A2A et ne
  résident plus comme rôles directement invoqués dans la JVM de l'orchestrateur.
- [ ] **A2A-224 — Documenter l'ajout d'un agent.** Catalogue, contrat, Agent Card, identité, task queue, service,
  permissions, observabilité, tests TCK et gate.
- [ ] **A2A-225 — Documenter l'exploitation locale.** Démarrage, diagnostic, cartes, traces, tâches, certificats,
  reset contrôlé et ressources Docker Desktop.
- [ ] **A2A-226 — Documenter l'exploitation GKE.** Déploiement, scaling, rotation, NetworkPolicies, incidents,
  drainage et rollback.
- [ ] **A2A-227 — Archiver les preuves.** Stocker rapports de tests, TCK, sécurité, performance, replay, cutover et
  approbation dans `docs/evidence/a2a/` avec digests.
- [ ] **A2A-228 — Clôturer l'ADR et le plan.** Marquer les décisions et tickets terminés, ajouter le commit final
  et lier la preuve de clôture.

## 20. Definition of Done globale

- [ ] Temporal est l'unique autorité de planification, attente, retry, annulation et reprise.
- [ ] A2A 1.0 est l'unique frontière d'invocation de tous les rôles d'agents du catalogue.
- [ ] Aucun agent ne peut appeler un pair sans décision et planification du workflow Temporal.
- [ ] Tous les appels d'outils passent encore par MCP avec l'identité et les permissions du rôle.
- [ ] Les contrats métier sont validés avant envoi et après réception indépendamment du protocole A2A.
- [ ] Les tâches A2A, corrélations, notifications et artefacts survivent aux redémarrages.
- [ ] L'idempotence est prouvée face aux retries, replays, pertes réseau et requêtes concurrentes.
- [ ] Les quatorze Agent Cards sont signées, allow-listées, conformes et cohérentes avec le catalogue.
- [ ] La sécurité inter-tenant, l'isolation des outils et l'absence de secrets dans les historiques sont prouvées.
- [ ] Les suites unitaires, contrats, TCK, intégration, E2E, sécurité, panne, performance et replay sont vertes.
- [ ] Docker Compose permet de développer et tester la topologie complète sur macOS.
- [ ] Les manifests GKE, NetworkPolicies, probes, autoscaling et procédures de rotation sont qualifiés.
- [ ] Les dashboards, alertes, SLO et runbooks SigNoz sont disponibles et testés.
- [ ] Le rollback revient à une release A2A compatible sans restaurer les appels directs.
- [ ] La gate de coupure et la clôture sont approuvées avec commit et digests exacts.

## 21. Ordre recommandé et dépendances

```text
ADR/version
   |
   v
baseline --> extraction agent-core --> contrats A2A --> Agent Cards
                                      |                  |
                                      v                  v
                              runtime A2A durable --> sécurité
                                      |
                                      v
                              raccordement Temporal
                                      |
                    +-----------------+-----------------+
                    v                                   v
              Compose/macOS                       GKE/plateforme
                    +-----------------+-----------------+
                                      v
                         observabilité + qualification
                                      |
                                      v
                              bascule franche
                                      |
                                      v
                          stabilisation + nettoyage
```

Règles de suivi recommandées :

- [ ] Une tâche cochée correspond à un changement vérifié, pas seulement commencé.
- [ ] Chaque ticket possède ses tests, sa preuve et son entrée de plan dans le même commit.
- [ ] Les commits utilisent l'identifiant du ticket, par exemple `feat(a2a): A2A-060 implement SendMessage`.
- [ ] Une case n'est cochée qu'après réussite des commandes de validation indiquées par le ticket.
- [ ] Les modifications sans rapport déjà présentes dans le worktree ne sont jamais intégrées aux commits A2A.

## 22. Références normatives

- [Spécification A2A 1.0](https://a2a-protocol.org/latest/specification/)
- [Nouveautés et incompatibilités A2A 1.0](https://a2a-protocol.org/latest/whats-new-v1/)
- [Relation entre A2A et MCP](https://a2a-protocol.org/latest/topics/a2a-and-mcp/)
- [SDK Java officiel A2A](https://github.com/a2aproject/a2a-java)
- [Releases du SDK Java A2A](https://github.com/a2aproject/a2a-java/releases)

Ces références doivent être réévaluées et leurs versions/digests consignés au début de l'implémentation : les
interfaces A2A et le SDK ne doivent jamais être consommés depuis une branche flottante ou une image non épinglée.
