# AI Factory

Prototype local d'usine logicielle agentique, exécuté avec Docker Compose. Les agents sont des services autonomes
adressables exclusivement avec **A2A 1.0**. Temporal est l'unique moteur de workflow : il planifie les délégations,
attend les résultats, applique les retries et porte les gates. Aucun runtime agent local ou fallback direct ne
subsiste dans l'orchestrateur.

Le mode `PIPELINE` reste disponible comme parcours métier séquentiel, mais ses étapes Planner, Developer,
PatchRepair, Tester et Reviewer sont elles aussi des tâches A2A. Le mode métier ne sélectionne jamais le transport.

Le chemin court historique reste :

`requirement -> plan -> patch -> validation du diff -> réparation si besoin -> sandbox -> tests -> SonarQube -> SBOM Syft -> scan Trivy -> review IA -> approbation humaine -> pull request Gitea`

## Objectifs fonctionnels et périmètre

L'usine transforme un besoin fonctionnel en une **proposition de changement vérifiée et traçable**. Elle automatise
la préparation d'une Pull Request, mais ne fusionne ni ne déploie le code en production.

| Objectif | Réponse apportée | Résultat observable |
|---|---|---|
| Accélérer un changement applicatif | Planification et génération de patch assistées par LLM | Plan, diff et branche de travail |
| Réduire le risque de régression | Validation Git, tests, quality gate et revue indépendante | Preuves liées à la tâche et au commit source |
| Contrôler la supply chain | SBOM CycloneDX et scan Trivy des vulnérabilités et secrets | Rapports Syft/Trivy bloquants |
| Garder l'humain responsable | Effet SCM suspendu jusqu'à une approbation explicite | Pull Request brouillon après approbation |
| Rendre l'agentique gouvernable | Rôles, contrats JSON, budgets, permissions et kill switch | Décisions auditables et refus fail-closed |
| Orchestrer durablement | Temporal coordonne chaque tâche A2A et chaque effet | Reprise, retry, annulation et corrélation durables |

Le périmètre actuel couvre les dépôts Maven, Gradle et npm. La fusion de PR, le déploiement, la gestion de
production et la remédiation automatique d'un finding de sécurité restent hors périmètre.

## Organisation du dépôt

| Répertoire | Contenu |
|---|---|
| `apps/` | Applications exécutables : orchestrateur, runtime agent A2A, noyau agent, serveurs MCP et interface web |
| `resources/` | Ressources métier versionnées : prompts, profils d'agents et modèle de ticket |
| `infrastructure/` | Compose, proxy, LiteLLM, sandbox et observabilité |
| `examples/` | Dépôts d'exemple utilisés pour les démonstrations |
| `scripts/` | Automatisation du bootstrap et de la démonstration |

Les commandes `make` restent lancées depuis la racine ; elles utilisent `infrastructure/compose.yaml`.

## Vue d'ensemble

La stack actuelle contient :

| Fonction | Composant |
|---|---|
| Point d'entrée HTTP | `reverse-proxy` Nginx (port 8080) |
| Interface de saisie & suivi | `factory-web` (SPA HTML/JS/CSS servie par Nginx) |
| Orchestration | Spring Boot 4.1 / Spring AI 2.0 / Java 25 (`orchestrator`) |
| Workflow | Temporal obligatoire ; workflow V1 et workers spécialisés actifs |
| Agents A2A | Quatorze rôles autonomes avec Agent Cards signées et task queues dédiées |
| Transport agent | A2A 1.0 JSON-RPC, mTLS et OAuth2 sur réseau privé |
| Mémoire de tâche | Projection PostgreSQL active, reconstruisible depuis Temporal et Evidence MCP |
| Contexte MCP | Serveur MCP stateless en lecture seule (`repository-context-mcp`) |
| Exécution MCP | Contrôleur de jobs à profils immuables (`sandbox-execution-mcp`) |
| Passerelle LLM | LiteLLM (port 4000 interne) |
| Modèle cloud | OpenAI via LiteLLM (`gpt-5.6-luna` configurable) |
| SCM / PR | Gitea + PostgreSQL 16 |
| Sandbox d'exécution | Conteneurs Docker éphémères (`ai-factory-sandbox:local`) |
| Build et tests | Maven / Gradle / npm selon le dépôt |
| Miroir d'artefacts | JFrog Artifactory OSS (dépôt Maven virtuel) |
| Qualité de code | SonarQube Community + PostgreSQL 16 |
| SBOM | Syft (CycloneDX JSON) |
| Scan sécurité | Trivy (vulnérabilités & secrets) |
| Observabilité | OpenTelemetry/OTLP + Collector 0.160 + SigNoz 0.135 (métriques, traces et logs) |

### Architecture multi-agent 1.2.0

```mermaid
flowchart LR
  U[Utilisateur] --> O[Orchestrateur]
  O --> T[Temporal]
  T -->|tâche A2A| S[Supervisor A2A]
  T -->|tâches A2A| A[Architecture / Code / Tests / Sécurité]
  T -->|tâche A2A| R[Independent Reviewer A2A]
  S -->|résultat A2A| T
  A -->|résultats A2A| T
  R -->|résultat A2A| T
  S --> M[Context / Evidence MCP]
  A --> M
  R --> M
  T -. seul propriétaire des effets .-> G[Sandbox / Assurance / SCM MCP]
  T --> H{Approbation humaine}
  H --> G
```

Le Supervisor propose un DAG borné ; Temporal valide puis planifie chaque délégation. Les entrées et résultats sont
des références Evidence digestées dont les contrats sont contrôlés avant et après l'échange A2A. Les agents
n'appellent jamais directement un pair et ne possèdent aucun outil à effet. Le workflow reste seul autorisé à
appliquer un patch, lancer les gates et livrer une Pull Request.

Compose démarre un runtime dédié par rôle avec sa propre identité, sa carte, ses secrets, ses permissions MCP et sa
task queue Temporal. Le même artefact `a2a-agent-runtime` charge un manifeste de rôle immuable au démarrage ; il ne
peut pas activer dynamiquement un second rôle.

### Architecture technique locale

```mermaid
flowchart TB
  User[Utilisateur] -->|HTTP :8080| Proxy[Reverse proxy Nginx]
  Proxy --> Web[SPA factory-web]
  Proxy --> API[Orchestrateur Spring Boot]

  subgraph Control[Plan de contrôle]
    API --> Temporal[Temporal obligatoire]
    Temporal -->|A2A 1.0| Agents[14 runtimes agents]
    Agents --> LLM[LiteLLM]
    LLM --> Cloud[Modèle OpenAI]
    Agents --> Context[Repository Context MCP]
    Agents --> Evidence[Evidence MCP]
    API --> Context[Repository Context MCP]
    API --> Sandbox[Sandbox Execution MCP]
    API --> Assurance[Assurance MCP]
    API --> Evidence[Evidence MCP]
    API --> SCM[SCM Delivery MCP]
    Temporal --> Projection[(Projection PostgreSQL)]
    Agents --> AgentState[(État A2A PostgreSQL)]
  end

  subgraph Execution[Plan d'exécution non fiable]
    Sandbox --> Jobs[Conteneurs sandbox éphémères]
    Jobs --> ProxyEgress[Proxy egress allow-list]
    Jobs --> Sonar[SonarQube]
    Jobs --> Artifactory[Artifactory]
  end

  SCM --> Gitea[Gitea]
  Evidence --> EvidenceVolume[(Volume de preuves)]
  API --> Workspace[(Workspaces de tâches)]
  API -->|OTLP| Collector[OpenTelemetry Collector]
  Agents -->|OTLP| Collector
  Context -->|OTLP| Collector
  Sandbox -->|OTLP| Collector
  Temporal -->|receiver de compatibilité| Collector
  Collector --> SigNoz[SigNoz]
```

Les cinq serveurs MCP séparent les capacités par nature : lecture du dépôt, exécution, évaluation, preuves et
livraison SCM. Les endpoints MCP ne sont pas publiés sur l'hôte. Le réseau Compose est lui-même segmenté entre
le trafic applicatif (`factory`), MCP (`mcp-internal`), workflow (`workflow-internal`) et les deux niveaux d'accès
des sandboxes (`sandbox-egress` et `sandbox-quality`).

> **État du prototype.** Temporal et A2A sont obligatoires pour toute nouvelle tâche. La readiness des quatorze
> rôles est contrôlée avant admission ; une dépendance indisponible suspend les tickets au lieu de sélectionner un
> chemin direct. Les projections orchestrateur et les associations A2A survivent aux redémarrages.

### Repères dans l'implémentation

| Responsabilité | Point d'entrée principal |
|---|---|
| API de tâches et commandes opérateur | [`TaskController`](apps/orchestrator/src/main/java/com/example/aifactory/controller/TaskController.java) |
| Admission et mémoire des tâches | [`TaskService`](apps/orchestrator/src/main/java/com/example/aifactory/service/TaskService.java), [`PostgresTaskMemory`](apps/orchestrator/src/main/java/com/example/aifactory/workflow/projection/PostgresTaskMemory.java) |
| Commandes de workflow | [`TemporalWorkflowCoordinator`](apps/orchestrator/src/main/java/com/example/aifactory/workflow/temporal/TemporalWorkflowCoordinator.java) |
| Client et activités A2A | [`A2aClientRuntimeConfiguration`](apps/orchestrator/src/main/java/com/example/aifactory/config/A2aClientRuntimeConfiguration.java), [`A2aActivitiesImpl`](apps/orchestrator/src/main/java/com/example/aifactory/workflow/temporal/A2aActivitiesImpl.java) |
| Runtime et identité des agents | [`AgentExecutionWorker`](apps/a2a-agent-runtime/src/main/java/com/example/aifactory/agentruntime/AgentExecutionWorker.java), [`RoleScopedAgentContext`](apps/agent-core/src/main/java/com/example/aifactory/agentcore/RoleScopedAgentContext.java) |
| Workflow durable actif | [`SoftwareFactoryExecutionWorkflowV1Impl`](apps/orchestrator/src/main/java/com/example/aifactory/workflow/temporal/SoftwareFactoryExecutionWorkflowV1Impl.java) |
| Contexte dépôt | [`McpRepositoryContextService`](apps/orchestrator/src/main/java/com/example/aifactory/service/McpRepositoryContextService.java) |
| Exécution isolée | [`McpSandboxService`](apps/orchestrator/src/main/java/com/example/aifactory/service/McpSandboxService.java), [`SandboxJobService`](apps/mcp/sandbox-execution-server/src/main/java/com/example/aifactory/sandbox/service/SandboxJobService.java) |
| Profils et limites sandbox | [`SandboxProfiles`](apps/mcp/sandbox-execution-server/src/main/java/com/example/aifactory/sandbox/service/SandboxProfiles.java), [`ComposeSandboxRuntime`](apps/mcp/sandbox-execution-server/src/main/java/com/example/aifactory/sandbox/service/ComposeSandboxRuntime.java), [`GkeSandboxRuntime`](apps/mcp/sandbox-execution-server/src/main/java/com/example/aifactory/sandbox/service/GkeSandboxRuntime.java) |
| Preuves et approbation durable | [`McpEvidenceRepository`](apps/orchestrator/src/main/java/com/example/aifactory/workflow/McpEvidenceRepository.java), [`EvidenceApprovalGate`](apps/orchestrator/src/main/java/com/example/aifactory/workflow/EvidenceApprovalGate.java) |
| Livraison Gitea | [`ScmDeliveryGateway`](apps/orchestrator/src/main/java/com/example/aifactory/service/ScmDeliveryGateway.java) |
| Configuration locale | [`compose.yaml`](infrastructure/compose.yaml), [`application.yml`](apps/orchestrator/src/main/resources/application.yml), [`.env.example`](.env.example) |

## Ce que fait réellement le prototype

Deux parcours métier sont représentés dans l'implémentation : le pipeline séquentiel et le DAG hiérarchique. Ils
partagent la même frontière d'exécution : Temporal ordonnance et chaque invocation d'agent traverse A2A 1.0. Le
pipeline n'est donc ni un transport alternatif, ni un fallback local.

1. L'utilisateur soumet un ticket depuis l'interface web (`factory-web`) ou via l'API REST `POST /api/tasks`.
2. L'orchestrateur attribue une référence durable (`AF-0001`, etc.), démarre le workflow Temporal puis clone le
   dépôt cible dans une activité spécialisée.
3. Temporal prépare une preuve d'entrée, résout l'Agent Card signée et soumet une tâche A2A au rôle requis.
4. Le runtime `architecture-agent` produit la feuille de route (`.ai-plan.md`) et publie son résultat dans Evidence.
5. Le runtime `developer` génère un patch `unified diff` via le même cycle A2A.
6. Le patch est normalisé (`UnifiedDiffNormalizer`), puis validé avec `git apply --check` dans une sandbox sans réseau.
7. En cas d'échec, Temporal délègue une nouvelle tâche A2A au rôle `patch-repair`, bornée à deux réparations.
8. Le patch est appliqué en sandbox, puis `git diff --check` et `git diff --stat` sont contrôlés.
9. Les tests unitaires/d'intégration s'exécutent dans la sandbox ; le rôle A2A `test-agent` analyse leur preuve déterministe avec un contrat JSON validé.
10. L'analyse de qualité SonarQube est déclenchée ; son quality gate est bloquant. En l'absence de jeton ou pour un type de projet non encore pris en charge, le run échoue au lieu de considérer le contrôle comme réussi.
11. Syft génère un SBOM CycloneDX (`.ai-factory/sbom.cdx.json`) et Trivy scanne les vulnérabilités/secrets (`.ai-factory/trivy.txt`) ; une détection HIGH ou CRITICAL est bloquante.
12. Le rôle A2A `independent-reviewer` synthétise les preuves dans `.ai-review.md`. Un rejet ou un finding `blocker` bloque le run.
13. La tâche passe au statut `WAITING_APPROVAL`.
14. Après approbation humaine (`POST /api/tasks/{id}/approve`), l'orchestrateur bascule sur une branche `ai-factory/<taskId>`, exclut les artefacts de travail IA (`git reset`), committe, pousse vers Gitea et ouvre une Pull Request.

```mermaid
sequenceDiagram
  autonumber
  actor U as Utilisateur
  participant API as Orchestrateur
  participant T as Temporal
  participant R as Runtime agent A2A
  participant C as Context MCP
  participant L as LiteLLM
  participant S as Sandbox MCP
  participant A as Assurance MCP
  participant M as SCM MCP
  participant G as Git / Gitea

  U->>API: POST /api/tasks
  API->>T: Démarrer le workflow
  T->>G: Cloner le commit source via activité
  T->>R: Envoyer la tâche A2A et sa référence Evidence
  R->>C: Lire le contexte autorisé via MCP
  R->>L: Exécuter le prompt du rôle
  R-->>T: Notifier le résultat A2A référencé
  T->>S: Valider/appliquer le patch et lancer les tests
  S-->>T: Retourner les preuves déterministes
  T->>A: Évaluer gates et politiques
  T->>R: Demander la revue indépendante via A2A
  R-->>T: Retourner la revue validée
  API-->>U: WAITING_APPROVAL
  U->>API: Approuver le manifeste
  API->>T: Signaler l'approbation
  T->>M: Demander la livraison approuvée
  M->>G: Créer branche, commit et PR brouillon
  M-->>T: URL de la Pull Request
  T-->>API: PR_CREATED
  API-->>U: PR_CREATED
```

## Pré-requis

- Docker Desktop ou Docker Engine avec Compose v2
- `make`, `curl`, `git`, `bash`
- JDK 25 et Maven 3.6.3+ pour compiler ou tester les modules Java hors Docker (les images Docker embarquent déjà Temurin 25)
- Python 3 (pour les scripts de bootstrap)
- `jq` recommandé pour manipuler les réponses API
- Environ 16 Go de RAM recommandés pour la stack complète

## Démarrage rapide

Pour une première installation ou une remise à zéro volontaire des volumes Docker :

```bash
make all
```

`make all` valide d'abord la configuration et les secrets, supprime ensuite les volumes Docker, construit toutes
les images, démarre les quatorze agents A2A, bootstrappe Gitea/SonarQube et applique la barrière de disponibilité.
Pour redémarrer en conservant les données existantes, utiliser `make up`.

URLs principales :

- Interface Web & API publique : `http://localhost:8080`
- Gitea : `http://localhost:3000` (dépôts de démonstration : `customer-api`, `inventory-gradle`, `checkout-node`)
- Orchestrateur direct (diagnostic & Actuator) : `http://localhost:8088`
- SonarQube : `http://localhost:9000`
- Artifactory : `http://localhost:8082`
- SigNoz : `http://localhost:3301` (compte initial généré par `make init`)

Le script `make bootstrap` initialise les comptes Gitea `aiadmin` et `reviewer`, pousse les trois dépôts de référence Maven, Gradle et Node depuis `examples/`, et génère automatiquement les jetons `GITEA_TOKEN` et `SONAR_TOKEN` dans le fichier `.env`.

## Utilisation

### Depuis l'interface web

L'interface `factory-web` est servie par le reverse proxy Nginx. Les appels API vers `/api/` sont redirigés de manière transparente vers l'orchestrateur.

L'interface permet de :

- rédiger un ticket structuré (résumé, objectif métier, périmètre, comportement actuel/attendu, critères d'acceptation) ;
- utiliser le bouton de pré-remplissage de démo ("Préremplir le modèle") ;
- utiliser le modèle cloud configuré derrière LiteLLM ;
- suivre la progression en temps réel (stepper, logs, progression) ;
- consulter l'historique complet des exécutions (vue "Exécutions") ;
- ouvrir le menu "Documentation" puis "Workflow" pour afficher le diagramme du pipeline ;
- inspecter la proposition (plan, patch, logs de tests, SonarQube, Trivy, revue IA) ;
- approuver la tâche et déclencher la PR.

### Depuis l'API

Créer une tâche :

```bash
curl -s -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{
    "repositoryUrl":"http://gitea:3000/aiadmin/customer-api.git",
    "baseBranch":"main",
    "requirement":"Add GET /customers/{id}. Return HTTP 404 when the customer does not exist. Add automated tests.",
    "llmMode":"CLOUD"
  }'
```

Consulter une tâche :

```bash
curl -s http://localhost:8080/api/tasks/<TASK_ID>
```

Lister les tâches :

```bash
curl -s http://localhost:8080/api/tasks
```

Approuver une tâche :

```bash
curl -s -X POST http://localhost:8080/api/tasks/<TASK_ID>/approve
```

Vérifier les capacités de l'usine :

```bash
curl -s http://localhost:8080/api/capabilities
```

## États de tâche

Les 14 statuts du cycle de vie d'une tâche sont :

1. `QUEUED` : Tâche enregistrée en mémoire.
2. `CLONING` : Clonage du dépôt Git.
3. `PLANNING` : Analyse du besoin et génération de la feuille de route par l'agent Planner.
4. `GENERATING_PATCH` : Génération du patch par l'agent Developer.
5. `APPLYING_PATCH` : Validation (`git apply --check`), réparation si nécessaire, et application du diff dans la sandbox.
6. `TESTING` : Exécution des tests automatisés dans la sandbox et analyse par l'agent Tester.
7. `QUALITY_SCANNING` : Analyse de qualité de code SonarQube.
8. `SECURITY_SCANNING` : Génération du SBOM CycloneDX (Syft) et scan vulnérabilités/secrets (Trivy).
9. `REVIEWING` : Synthèse globale des preuves déterministes par l'agent Reviewer.
10. `WAITING_APPROVAL` : En attente de l'approbation humaine.
11. `APPROVED` : Validation humaine enregistrée.
12. `PR_CREATED` : Branche créée, commit effectué, push réalisé et Pull Request ouverte sur Gitea.
13. `CANCELLED` : Annulation explicite de la tâche par un opérateur.
14. `FAILED` : Échec rencontré à l'une des étapes (diff invalide non réparable, erreur de build, etc.).

## Modèle LLM

Les runtimes agents appellent LiteLLM vers le modèle cloud `factory-code-cloud` (`gpt-5.6-luna` par défaut).
L'orchestrateur ne demande aucune complétion : il ne conserve qu'une sonde réactive de disponibilité du fournisseur.

## Contexte dépôt via MCP

Le serveur MCP de contexte fournit cinq outils en lecture seule : `context.list_tree`, `context.search_code`,
`context.read_file`, `context.get_repository_rules` et `context.get_dependencies`. Ce dernier lit uniquement les
dépendances directes déclarées dans `pom.xml`, `build.gradle(.kts)` ou `package.json`, sans lancer de build ni
télécharger de dépendance. Le serveur vérifie que chaque demande cible le workspace
d'une tâche et son commit Git immuable, borne les résultats, exclut les chemins sensibles et refuse les sorties
de workspace par traversal ou lien symbolique.

Après validation de MCP-057, le contexte dépôt est exclusivement fourni par MCP :

```bash
AI_FACTORY_MCP_ENABLED=true
AI_FACTORY_MCP_REPOSITORY_CONTEXT_MODE=MCP_ACTIVE
AI_FACTORY_MCP_REPOSITORY_CONTEXT_ACTIVE_ROLES=planner,developer,patch-repair
```

Les anciennes valeurs `DIRECT` et `MCP_SHADOW` sont conservées dans l'énumération de configuration pour détecter
explicitement une configuration obsolète, mais elles sont refusées lors d'une collecte. Une erreur MCP bloque la
contextualisation sans fallback. Le endpoint MCP reste privé au réseau Compose et n'est pas publié
sur un port hôte. L'authentification du transport fait partie du chantier de durcissement avant toute exposition.

## Exécution sandbox via MCP

Dans la configuration Compose, les validations de patch, tests, analyses SonarQube, SBOM et scans Trivy passent
par `sandbox-execution-mcp`. L'orchestrateur ne monte plus le socket Docker et ne reçoit plus les secrets SonarQube
ou Artifactory nécessaires aux jobs. Les commandes et contraintes sont définies par cinq profils serveur immuables ;
les appels MCP ne peuvent fournir ni shell, ni image, ni réseau, ni volume, ni variable d'environnement.
Les valeurs d'environnement configurées côté serveur doivent être monolignes (CR/LF/NUL refusés). Elles sont
injectées uniquement dans le runner correspondant au profil. Les noms de fichiers et le contenu du patch sont
uniquement lus par Git : les métacaractères shell qu'ils contiennent ne sont pas évalués.

```bash
AI_FACTORY_MCP_CLIENT_ENABLED=true
AI_FACTORY_MCP_SANDBOX_ENABLED=true
AI_FACTORY_MCP_SANDBOX_MODE=MCP_ACTIVE
AI_FACTORY_MCP_SANDBOX_ACTIVE_OPERATIONS=validate_patch,apply_patch,run_tests,run_quality,run_security
```

Le chemin Docker historique de l'orchestrateur a été supprimé. Une opération absente de la liste, un serveur MCP
désactivé ou un ancien mode `DIRECT`/`MCP_SHADOW` échoue fermé, sans réintroduire la socket ou les secrets.

Le mode local utilise quatre runners Compose statiques, séparés selon leurs droits workspace et réseau. Ils sont
non-root, read-only, sans capabilities, sans port hôte et n'acceptent que des identifiants de profils signés par un
jeton local. Aucun composant applicatif ne contrôle le daemon Docker. La cible partagée utilise le contrôleur de Jobs
GKE avec RBAC, Pod Security, gVisor, quotas et NetworkPolicies dédiés.

Les états bornés et déjà redacted des jobs sont écrits atomiquement dans le volume dédié `sandbox-job-state`.
Après un redémarrage, les résultats terminaux et les clés d'idempotence sont restaurés ; toute exécution qui était
encore active devient `FAILED / INDETERMINATE`, et les processus orphelins annoncés par les runners sont annulés.
Les états terminaux expirent depuis leur `completed_at` après `AI_FACTORY_SANDBOX_JOB_RETENTION` (`P7D` par défaut,
valeur autorisée de 1 minute à 365 jours). La purge du snapshot, du handle et de sa clé d'idempotence s'effectue au
démarrage, avant les opérations MCP et périodiquement ; une nouvelle soumission après expiration reçoit donc un
nouvel `execution_id`. Les exécutions actives ne sont jamais supprimées par cette rétention.
L'admission est bornée par `AI_FACTORY_SANDBOX_MAX_CONCURRENT_JOBS` exécutions simultanées,
`AI_FACTORY_SANDBOX_MAX_QUEUED_JOBS` jobs en attente et `AI_FACTORY_SANDBOX_MAX_ACTIVE_JOBS_PER_TASK` jobs actifs
pour une même tâche. Une soumission idempotente retrouve toujours son job existant ; une nouvelle soumission hors
quota est refusée immédiatement, sans snapshot orphelin. Les jauges `ai_factory_sandbox_jobs_running` et
`ai_factory_sandbox_jobs_queued`, le timer `ai_factory_sandbox_job_queue_duration` et le compteur de rejets par
raison exposent la pression du contrôleur.
Pendant `ACCEPTED` et `RUNNING`, `heartbeat_at` est rafraîchi et persisté toutes les
`AI_FACTORY_SANDBOX_HEARTBEAT_INTERVAL` (`PT15S` par défaut) ; l'orchestrateur refuse un heartbeat absent, invalide
ou plus ancien que son timeout de polling. `sandbox.get_execution` retourne les logs déjà redacted par pages de
4 096 caractères par défaut, jusqu'à 16 384 via `output_limit`, avec `next_output_cursor`, la taille totale retenue
et `output_truncated` lorsque la borne globale a supprimé le début du flux. Le client reconstruit les pages avec une
limite locale et refuse les curseurs incohérents. Chaque résultat expose aussi `evidence_status` (`NONE`, `PARTIAL`
ou `COMPLETE`) et le SHA-256 `output_digest` calculé après redaction sur l'intégralité de la sortie retenue. Une
sortie tronquée ou interrompue par timeout est persistée comme preuve `PARTIAL` avec un verdict `INDETERMINATE` ;
elle reste consultable mais ne peut pas valider le workflow. L'orchestrateur recalcule le digest après pagination et
refuse toute preuve partielle, absente ou altérée.

Le test d'intégration opt-in suivant demande un daemon Docker local et l'image `ai-factory-sandbox:local`. Il crée
un conteneur et un volume aux noms aléatoires, vérifie les limites réellement acceptées par Docker, puis les supprime
systématiquement :

```bash
make test-sandbox-runtime
```

Le mode cloud n'est accessible que si `AI_FACTORY_CLOUD_ENABLED=true` dans `.env`.

Variables de configuration principales :

```bash
OPENAI_MODEL=gpt-5.6-luna
AI_FACTORY_CLOUD_ENABLED=true
LITELLM_MASTER_KEY=local-dev-litellm-key
MAVEN_MIRROR_URL=
AI_FACTORY_SANDBOX_MAVEN_MIRROR_URL=https://repo.maven.apache.org/maven2
NPM_REGISTRY_URL=https://registry.npmjs.org/
NPM_REGISTRY_HOST=registry.npmjs.org
```

`MAVEN_MIRROR_URL` est le miroir optionnel utilisé lors de la construction des images. Les profils sandbox exigent
en revanche des endpoints explicites : Maven Central et le registre npm public sont proposés dans `.env.example`.
En environnement d'entreprise, remplacez-les par les endpoints autorisés ; le `settings.xml` et le jeton
Artifactory ne sont chargés que lorsqu'un miroir Maven authentifié est configuré.

Pour utiliser le mode cloud, placez votre clé OpenAI dans le fichier `.vault` :
```bash
VAULT_OPENAI_API_KEY=sk-...
```
Ce fichier est exclu du contrôle de version Git, créé avec des permissions locales restrictives et chargé au
runtime par LiteLLM. Il s'agit d'un mécanisme de développement local, pas d'un coffre de secrets de production.

Si un proxy d'entreprise intercepte le trafic HTTPS et présente un certificat interne, LiteLLM ajoute au démarrage la chaîne présentée par `api.openai.com:443` à son bundle de confiance. Pour employer un autre endpoint, configurez dans `.env` :
```bash
OPENAI_CA_CERT_HOST=api.openai.com:443
```
Cette étape n'est exécutée que lorsqu'une clé OpenAI est configurée et la vérification TLS reste active.
Les certificats d'interception historiques qui n'ont pas d'Authority Key Identifier restent compatibles avec Python 3.13 ; la chaîne, la signature et le nom d'hôte restent vérifiés.

## Sécurité et frontières de confiance

Le dépôt, le ticket, les sorties LLM et les logs de build sont traités comme des données non fiables. Ils peuvent
contenir du code malveillant, des secrets ou des instructions de prompt injection ; ils ne deviennent jamais une
autorité de décision simplement parce qu'un agent les a produits.

```mermaid
flowchart LR
  subgraph Untrusted[Entrées non fiables]
    Ticket[Ticket utilisateur]
    Repo[Dépôt source]
    Model[Sortie du modèle]
  end

  subgraph Policy[Plan de contrôle de confiance]
    Contracts[Validation des contrats]
    Permissions[Permissions par rôle]
    Budgets[Budgets et quotas]
    Coordinator[Workflow Coordinator]
    Audit[Journal de sécurité chaîné]
  end

  subgraph Effects[Effets contrôlés]
    Sandbox[Profils sandbox immuables]
    Gates[Tests / qualité / sécurité]
    PR[Création de PR]
  end

  Ticket --> Contracts
  Repo --> Contracts
  Model --> Contracts
  Contracts --> Permissions --> Budgets --> Coordinator
  Coordinator --> Sandbox --> Gates
  Gates --> Approval{Approbation humaine}
  Approval -->|accord| PR
  Approval -->|refus| Stop[Arrêt sans effet SCM]
  Permissions --> Audit
  Coordinator --> Audit
  Approval --> Audit
```

### Règles appliquées

| Domaine | Règle |
|---|---|
| Autorité | Le rôle vient de l'identité du workflow, jamais de la réponse du modèle. La matrice d'outils est deny-by-default. |
| Effets | Les agents spécialistes n'ont accès qu'au contexte et aux preuves ; seul le workflow peut appliquer, tester, scanner, écrire les preuves ou créer une PR. |
| Contrats | Les sorties d'agents sont validées contre des schémas versionnés et bornées à 1 MiB. Un contrat invalide est refusé. |
| MCP | Nom/version du serveur, protocole, audience, outil et taille de réponse sont contrôlés. Une indisponibilité ne déclenche pas de fallback direct. |
| Sandbox | Aucun appelant ne fournit de commande, image, volume, réseau ou variable d'environnement ; il choisit seulement une opération allow-listée. |
| Conteneurs | `--cap-drop ALL`, `no-new-privileges`, 2 CPU, 2 Gio et 512 PID ; validation et application du patch sans réseau. |
| Réseau | Les sorties externes des tests et scans passent par un proxy Squid limité aux registries nécessaires ; SonarQube n'est accessible qu'au profil qualité. |
| Secrets | `.env` et `.vault` ne sont pas versionnés. Les secrets de build utilisent BuildKit ; ceux des jobs sandbox restent côté serveur MCP et sont injectés par fichier temporaire `0600`. |
| Preuves | Logs redacted, pagination bornée, digest SHA-256 recalculé côté orchestrateur ; une preuve absente, partielle, tronquée ou incohérente ne valide pas un gate. |
| Livraison | Une approbation humaine précède toujours l'effet SCM. La passerelle signe ensuite une preuve à durée limitée liée au commit source, au patch et aux preuves ; le chemin durable sait en plus imposer l'approbation d'un manifeste immuable. |
| Arrêt d'urgence | Le code du kill switch peut interdire un serveur, un outil, un rôle ou un mode ; son fichier de contrôle n'est toutefois pas monté par le Compose courant. |

Le journal d'audit de sécurité est chaîné en mémoire avec HMAC-SHA-256 afin de rendre les altérations détectables
pendant la vie du processus. Cette propriété ne remplace toutefois ni la persistance, ni un stockage WORM, ni
l'export vers un SIEM dans une cible de production.

### Risques résiduels du déploiement local

- les runners Compose sont persistants et offrent une isolation plus faible que gVisor ou une microVM ; ils restent réservés au développement local ;
- les transports MCP sont confinés aux réseaux Compose mais ne disposent pas encore d'une authentification forte ;
- l'API publique n'implémente pas encore SSO, RBAC, séparation multi-tenant ni rate limiting par utilisateur ;
- le pipeline de référence utilise encore l'approbation simple ; l'endpoint d'approbation lié à un manifeste est
  présent mais appartient au chemin durable non généralisé ;
- `.vault` est un fichier local et les mots de passe par défaut des services de développement ne conviennent pas à
  une exposition réseau ;
- les volumes Docker assurent la persistance locale, pas la sauvegarde, le chiffrement géré ou un plan de reprise.

La stack doit donc rester liée à la boucle locale ou à un environnement de démonstration isolé.

## Quotas, budgets et timeouts

Toutes les valeurs ci-dessous sont des **plafonds par défaut versionnés**. Leur dépassement provoque un refus ou un
résultat indéterminé ; il n'augmente jamais silencieusement la capacité demandée.

### Gouvernance agentique

| Niveau | Plafond par défaut |
|---|---|
| DAG de délégation | profondeur 2, fan-out 4, chemin critique 2 700 s |
| Une délégation | 6 tours, 12 000 tokens, 12 000 000 micro-unités de coût, 900 s, 24 appels outil |
| Une tâche | 60 tours, 80 000 tokens planifiés, 80 000 000 micro-unités de coût, 208 appels outil |
| Usage réel cumulé | 120 000 tokens d'entrée, 40 000 de sortie, 80 000 000 micro-unités de coût, 60 tours, 208 appels MCP |
| Réserve de finalisation | 10 000 tokens d'entrée, 5 000 de sortie, 10 000 000 micro-unités de coût, 6 tours, 32 appels MCP |

Chaque rôle et chaque périmètre Architecture/Code/Tests/Sécurité possède en plus un plafond inférieur ou égal à
ces limites. La politique faisant foi est
[`resources/multiagents/policies/hierarchical-budget-policy-v1.yaml`](resources/multiagents/policies/hierarchical-budget-policy-v1.yaml).

### MCP et contexte dépôt

| Ressource | Valeur par défaut | Variable principale |
|---|---:|---|
| Réponse MCP | 65 536 octets | `AI_FACTORY_MCP_MAX_RESPONSE_BYTES` |
| Appels MCP simultanés | 32 globaux, 16/serveur, 4/tâche, 8/rôle | `AI_FACTORY_MCP_MAX_INFLIGHT_*` |
| Timeout d'une requête MCP | 20 s | `AI_FACTORY_MCP_REQUEST_TIMEOUT` |
| Tentatives lecture / effet | 3 / 2, avec backoff et jitter | `AI_FACTORY_MCP_*_RETRY_MAX_ATTEMPTS` |
| Fichier lu par Context MCP | 1 MiB | `AI_FACTORY_CONTEXT_MAX_FILE_BYTES` |
| Recherche / arbre du dépôt | 1 000 fichiers / 1 000 entrées | `AI_FACTORY_CONTEXT_MAX_SEARCH_FILES`, `AI_FACTORY_CONTEXT_MAX_TREE_ENTRIES` |
| Capacité déclarée d'une task queue | 4 workers | `AI_FACTORY_TASK_QUEUE_WORKER_CAPACITY` |

### Jobs sandbox

| Ressource | Valeur par défaut | Variable ou politique |
|---|---:|---|
| Jobs exécutés / en attente | 2 / 32 | `AI_FACTORY_SANDBOX_MAX_CONCURRENT_JOBS`, `AI_FACTORY_SANDBOX_MAX_QUEUED_JOBS` |
| Jobs actifs par tâche | 2 | `AI_FACTORY_SANDBOX_MAX_ACTIVE_JOBS_PER_TASK` |
| États de jobs conservés | 500 pendant 7 jours | `AI_FACTORY_SANDBOX_MAX_JOBS`, `AI_FACTORY_SANDBOX_JOB_RETENTION` |
| Sortie conservée / patch accepté | 1 MiB / 1 MiB | `AI_FACTORY_SANDBOX_MAX_OUTPUT_CHARS`, `AI_FACTORY_SANDBOX_MAX_PATCH_BYTES` |
| Ressources d'un conteneur | 2 CPU, 2 Gio, 512 PID | profil serveur immuable |
| Validation / application d'un patch | 3 min / 3 min | profil serveur immuable |
| Tests / qualité / sécurité | 15 min / 15 min / 10 min | profil serveur immuable |
| Heartbeat / polling orchestrateur | 15 s / 20 min | `AI_FACTORY_SANDBOX_HEARTBEAT_INTERVAL`, `AI_FACTORY_MCP_SANDBOX_POLL_TIMEOUT` |

Les quotas d'admission sont évalués avant la création du snapshot. Une répétition avec la même clé d'idempotence
retrouve le job existant ; une nouvelle demande au-delà du plafond est refusée immédiatement.

## Choix de déploiement

### Déploiement fourni : Docker Compose local

Compose privilégie la reproductibilité et la lisibilité des frontières : un seul hôte, des services nommés, cinq
réseaux dédiés et des volumes persistants. C'est le bon format pour développer, démontrer les gates et qualifier
les contrats, mais pas pour isoler fortement du code hostile ou garantir haute disponibilité et reprise d'activité.

### Cible d'industrialisation

La cible documentée n'est pas livrée sous forme de manifests de production dans ce dépôt. Elle conserve les
contrats MCP pour pouvoir remplacer les backends sans donner davantage de pouvoir aux agents.

| Capacité | Local actuel | Cible recommandée | Motivation |
|---|---|---|---|
| Web et API | Nginx + Spring sur Compose | Cloud Run ou GKE derrière HTTPS/IAP | Authentification, autoscaling et exposition maîtrisée |
| Agents | 14 runtimes A2A isolés par rôle | Déploiements GKE dédiés avec Workload Identity | Identité, permissions, scaling et blast radius par rôle |
| Workflow | Temporal local, workers séparés par task queue | Temporal managé ou opéré avec la même topologie | Reprise durable, signaux humains, retries et versionnement |
| État | Mémoire JVM et volumes locaux | PostgreSQL/Cloud SQL + stockage objet des preuves | Transactions, sauvegardes, rétention et restauration |
| Sandbox | Runners Compose statiques sans socket Docker | GKE dédié avec gVisor/Agent Sandbox et Jobs éphémères | Séparer le code non fiable du plan de contrôle |
| Réseau | Réseaux Compose et proxy Squid allow-listé | `default deny`, egress explicite, VPC séparé, aucun accès metadata/control plane | Réduire mouvement latéral et exfiltration |
| Identités et secrets | Fichiers `.env`/`.vault` | Workload Identity et Secret Manager | Identités courtes, minimales et auditables |
| SCM, qualité, artefacts | Gitea, SonarQube et Artifactory locaux | Services d'entreprise via adaptateurs MCP | Conserver les politiques et systèmes de référence |
| Observabilité | Collector OpenTelemetry et SigNoz locaux | Google Cloud Monitoring, Trace et Logging via gateway OTel | SLO, investigation et conformité |

La bascule de transport est complète : tous les parcours appellent les agents par A2A. Une indisponibilité ferme
les admissions et le rollback redéploie uniquement une version antérieure déjà compatible A2A.

## Démonstration

Lancer une tâche de démo pré-configurée :

```bash
make demo
```

## Qualité et observabilité

- **SonarQube** (`http://localhost:9000`) : Analyse de la qualité du code Java/Maven. Les jetons sont générés par `make bootstrap` ou `make tokens`.
- **Artifactory** (`http://localhost:8082`) : Dépôt d'artefacts local. Les builds Maven des sandboxes utilisent le miroir explicite `MAVEN_MIRROR_URL`.
- **SigNoz** (`http://localhost:3301`) : reçoit métriques, traces et logs via le Collector OpenTelemetry. Neuf dashboards et trente alertes couvrent la plateforme, la flotte A2A et les appels LLM ; ils sont provisionnés automatiquement.
- **Collector OpenTelemetry** : reçoit l'OTLP de l'orchestrateur, des cinq MCP et des quatorze runtimes A2A, collecte les métriques Temporal par un receiver de compatibilité interne et n'expose aucun port à l'hôte.

## Commandes Make disponibles

| Commande | Description |
|---|---|
| `make help` | Affiche l'aide des commandes Make |
| `make init` | Initialise `.env` et `.vault`, puis génère les secrets locaux obligatoires absents |
| `make a2a-config` | Valide la topologie, les rôles et les frontières de capacités A2A |
| `make a2a-status` | Affiche l'état de la base A2A et des quatorze runtimes |
| `make a2a-cards` | Télécharge et valide les quatorze Agent Cards privées |
| `make a2a-smoke` | Vérifie services, task queues et Agent Cards |
| `make a2a-up-full` | Démarre explicitement le profil complet des agents A2A |
| `make test-a2a-temporal` | Vérifie le Build ID, les 14 task queues et les 28 pollers A2A |
| `make verify-ready` | Applique la barrière finale Temporal/A2A/Agent Cards/admissions |
| `make build` | Construit toutes les images nécessaires à la fabrique A2A |
| `make up` | Construit, démarre et vérifie la stack complète en conservant les volumes |
| `make all` | Supprime les volumes Docker puis reconstruit et bootstrappe une usine A2A complète |
| `make bootstrap` | Initialise Gitea/SonarQube, recrée les consommateurs et revalide la stack |
| `make tokens` | Met à jour les jetons, recrée les consommateurs et revalide la stack |
| `make demo` | Soumet une tâche de démo à l'orchestrateur |
| `make test` | Exécute les tests de l'orchestrateur et des serveurs MCP |
| `make test-sandbox-runtime` | Vérifie les contraintes effectives des conteneurs sandbox |
| `make mcp-shadow-campaign` | Valide le corpus shadow de 20 tâches sans l'exécuter |
| `make mcp-active-campaign` | Exécute le canary `MCP_ACTIVE` limité aux rôles autorisés |
| `make mcp-shadow-campaign CAMPAIGN_ARGS=--execute AI_FACTORY_RUN_CLOUD_CAMPAIGN=true` | Exécute volontairement la campagne cloud séquentielle |
| `make mcp-shadow-report` | Génère le rapport des métriques shadow courantes |
| `make package` | Compile et empaquette l'orchestrateur Java (sans tests) |
| `make config` | Valide Compose ainsi que la topologie et les réseaux A2A |
| `make status` | Affiche les services et jobs one-shot du profil `a2a-full` |
| `make restart` | Recrée l'orchestrateur, réactive son Build ID et vérifie Temporal/A2A |
| `make logs` | Suit les journaux de l'orchestrateur |
| `make urls` | Liste toutes les URLs de services et points d'accès |
| `make down` | Arrête tous les profils A2A en conservant les volumes |
| `make clean` | Arrête tous les profils et supprime tous les volumes Docker (destructif) |

## Limites actuelles

- le raccordement A2A local est complet, mais la qualification de la cible GKE requiert toujours un cluster et
  des identités Workload Identity disponibles ;
- le Compose local reste mono-hôte ; la haute disponibilité et le PRA doivent être qualifiés sur l'infrastructure
  cible ;
- les projections PostgreSQL, associations A2A et Evidence sont intégrées ; leur exploitation managée et leur
  restauration sur la cible GKE restent à valider ;
- les prompts, cartes, contrats et politiques sont versionnés ; leur promotion reste soumise aux propriétaires
  humains désignés ;
- support des builds limité à Maven, Gradle et npm ;
- pas de SSO, RBAC ni policy engine ;
- approbation humaine obligatoire avant push/PR ;

Les règles de confiance des prompts, la validation des contrats de sortie et les gates de tests, qualité et
sécurité ont été renforcés dans le prototype. Ils ne remplacent pas le SSO/RBAC, un moteur de policy-as-code,
ni une sandbox de production : ces limites restent bloquantes pour un usage entreprise exposé.

## Documentation complémentaire

- [Guide de lecture et statut des documents](docs/README.md)
- [Rétrodocumentation complète : fonctionnel, architecture, sécurité, données et packaging](docs/overview/current-state.md)
- [État, architecture et workflow du prototype 1.2.0](docs/archive/releases/1.2.0-archi-04/ETAT-PROTO-1.2.0.md)
- [Architecture cible multi-agent hiérarchique](docs/archive/releases/1.2.0-archi-04/cible-architecture-multi-agent-hierarchique.md)
- [Plan de bascule](docs/archive/releases/1.2.0-archi-04/BASCULE-ARCHI-04-MULTI-AGENTS.md)
- [Catalogue des agents](docs/architecture/agents/CATALOGUE-AGENTS-V1.md)
- [Architecture A2A et frontières de confiance](docs/architecture/a2a/README.md)
- [Exploitation locale A2A sur macOS](docs/development/a2a-macos.md)
- [Plan de migration A2A/Temporal](docs/delivery/migrations/migration-agents-a2a-temporal.md)
- [Architecture, workflow et sécurité de la baseline 1.1.0](docs/archive/releases/1.1.0-archi-02-mcp/ETAT-PROTO-1.1.0.md)
