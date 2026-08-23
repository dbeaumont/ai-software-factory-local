# AI Software Factory locale — prototype Docker

## 1. Objectif

Ce prototype matérialise, sur une seule machine, une version simplifiée d'une **usine de production logicielle basée sur l'IA**. L'objectif n'est pas de reproduire immédiatement toute la complexité d'une plateforme d'entreprise, mais de valider les principes structurants de l'architecture cible : orchestration agentique, contextualisation du code, exécution isolée, contrôles déterministes, sécurité, SBOM, revue et validation humaine avant création d'une Pull Request.

Le flux nominal est le suivant :

```mermaid
flowchart LR
    R[Requirement] --> P[Planner Agent]
    P --> D[Developer Agent]
    D --> PATCH[Unified Diff]
    PATCH --> S[Sandbox Docker]
    S --> B[Build & Tests]
    B --> T[Tester Agent]
    T --> SEC[Syft + Trivy]
    SEC --> REV[Reviewer Agent]
    REV --> H{Validation humaine}
    H -->|Approve| PR[Branche + Commit + PR Gitea]
    H -->|Reject| STOP[Arrêt / correction]
```

Le principe essentiel du prototype est le même que celui de la cible d'entreprise :

> **L'IA propose et exécute ; les contrôles déterministes et les validations gouvernées décident si le changement peut progresser.**

---

## 2. Pourquoi Docker Compose pour le prototype

Docker Compose est adapté à une première implémentation locale pour plusieurs raisons :

- mise en œuvre rapide ;
- reproductibilité de l'environnement ;
- isolation des composants ;
- déploiement sur un seul poste ;
- possibilité de simuler les plans de contrôle, d'exécution et d'assurance ;
- transition naturelle vers Kubernetes lorsque le POC est validé.

Le prototype repose donc sur des services persistants orchestrés par Docker Compose, complétés par des **containers éphémères** créés à la demande pour l'exécution des tâches agentiques.

---

## 3. Architecture fonctionnelle simplifiée

```mermaid
flowchart TB
    DEV[Développeur / Architecte\nVS Code / Browser]
    GIT[Gitea\nRepositories • Issues • PR]
    ORCH[Agent Orchestrator\nSpring Boot / Java 21]
    LLM[LLM local\nOllama]
    CTX[Context / Repository Loader]
    SB[Docker Agent Sandbox\nContainer éphémère]
    CI[Quality Gates\nBuild • Tests • Syft • Trivy]
    SONAR[SonarQube\nprofil full]
    NEXUS[Nexus Repository\nprofil full]
    OBS[Prometheus + Grafana\nprofil full]

    DEV --> GIT
    GIT --> ORCH
    ORCH --> LLM
    ORCH --> CTX
    ORCH --> SB
    CTX --> ORCH
    SB --> CI
    CI --> ORCH
    SONAR -. qualité .-> CI
    NEXUS -. dépendances / artefacts .-> SB
    OBS -. métriques .-> ORCH
    ORCH -->|après approbation| GIT
```

---

## 4. Architecture technique cible du prototype

La version locale conserve les mêmes plans logiques que l'architecture d'entreprise, mais avec des implémentations volontairement légères.

```mermaid
flowchart TB
    subgraph L1[1. Canaux d'entrée / Product & Engineering]
        U[Développeurs / Architectes]
        J[Jira / Issues\nreprésenté par Gitea Issues]
        DOC[Confluence / ADR\nreprésenté par docs du repo]
        CAT[Service Catalog\noptionnel dans le MVP]
    end

    subgraph L2[2. Control Plane / Orchestration]
        ORCH[Spring Boot Orchestrator]
        POL[Policies simples]
        APP[Approbation humaine]
        AUD[Logs / traces]
    end

    subgraph L3[3. Agent & Model Plane]
        PL[Planner]
        DEVAG[Developer]
        TESTAG[Tester]
        REV[Reviewer]
        OLL[Ollama]
    end

    subgraph L4[4. Context Plane]
        REPO[Git repository]
        SRC[Code source]
        ADR[README / docs / ADR]
        MCP[MCP Gateway\nfuture extension]
    end

    subgraph L5[5. Execution Plane]
        SANDBOX[Container Docker éphémère]
        CLONE[git clone]
        BUILD[build]
        TESTS[tests]
        APPLY[git apply]
    end

    subgraph L6[6. Delivery / Assurance Plane]
        SYFT[Syft / SBOM CycloneDX]
        TRIVY[Trivy\nvulnérabilités + secrets]
        SONAR[SonarQube\nprofil full]
        NEXUS[Nexus\nprofil full]
        PR[Pull Request Gitea]
    end

    U --> ORCH
    J --> ORCH
    DOC --> ORCH
    ORCH --> PL
    ORCH --> DEVAG
    ORCH --> TESTAG
    ORCH --> REV
    PL --> OLL
    DEVAG --> OLL
    TESTAG --> OLL
    REV --> OLL
    REPO --> ORCH
    SRC --> ORCH
    ADR --> ORCH
    MCP -. futur .-> ORCH
    ORCH --> SANDBOX
    SANDBOX --> CLONE --> APPLY --> BUILD --> TESTS
    TESTS --> SYFT
    TESTS --> TRIVY
    TESTS -. optionnel .-> SONAR
    BUILD -. artefacts .-> NEXUS
    SYFT --> APP
    TRIVY --> APP
    SONAR --> APP
    APP -->|approve| PR
```

---

## 5. Composants du prototype

| Fonction | Composant | Rôle dans le POC |
|---|---|---|
| SCM / Issues / PR | Gitea | Dépôts Git, tickets, branches, Pull Requests |
| LLM local | Ollama | Exécution des rôles agentiques |
| Orchestration | Spring Boot 3.5 / Java 21 | Pilotage du workflow, appels LLM, sandboxes, états de tâche |
| Sandbox | Docker | Exécution isolée et éphémère des modifications |
| Tests | Maven / Gradle / npm | Validation fonctionnelle de base |
| SBOM | Syft / CycloneDX | Inventaire des composants logiciels |
| Vulnérabilités / secrets | Trivy | Analyse de sécurité |
| Qualité | SonarQube | Analyse qualité avancée, profil `full` |
| Artefacts | Nexus Repository | Proxy / dépôt d'artefacts, profil `full` |
| Observabilité | Spring Actuator + Prometheus + Grafana | Métriques techniques, profil `full` |

Le MVP ne cherche pas à intégrer immédiatement GitLab Duo, GitHub AI Controls ou un MCP Gateway complet. Il reproduit d'abord les **mécanismes architecturaux** afin de valider le modèle avant de remplacer les briques locales par leurs équivalents d'entreprise.

---

## 6. Organisation du projet

```text
ai-software-factory-local/
├── docker-compose.yml
├── Makefile
├── .env.example
├── orchestrator/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/...
├── sandbox/
│   └── Dockerfile
├── agents/
│   ├── planner.yaml
│   ├── developer.yaml
│   ├── tester.yaml
│   └── reviewer.yaml
├── prompts/
│   ├── planner.md
│   ├── developer.md
│   ├── tester.md
│   └── reviewer.md
├── sample-repo/
│   └── ...
├── scripts/
│   ├── bootstrap-gitea.sh
│   └── demo.sh
├── observability/
│   └── prometheus.yml
└── docs/
    ├── architecture.md
    ├── security.md
    └── AI_SOFTWARE_FACTORY_LOCAL.md
```

---

## 7. Principe d'orchestration agentique

Le premier prototype utilise **un seul LLM**, mais plusieurs rôles logiques. Cette approche évite de complexifier inutilement l'architecture dès le départ.

```mermaid
flowchart LR
    O[Orchestrateur]
    L[Ollama / modèle unique]

    O -->|prompt Planner| L
    L -->|plan| O
    O -->|prompt Developer| L
    L -->|patch| O
    O -->|prompt Tester| L
    L -->|analyse tests| O
    O -->|prompt Reviewer| L
    L -->|review| O
```

Les rôles sont définis par des prompts et des fichiers YAML distincts :

- **Planner** : comprend la demande et produit un plan de modification ;
- **Developer** : génère un patch strict au format unified diff ;
- **Tester** : examine les résultats de tests et complète l'analyse ;
- **Reviewer** : réalise une revue finale basée sur les preuves d'exécution.

Cette séparation permet ensuite d'affecter des modèles différents à chaque rôle sans modifier le workflow global.

---

## 8. Flux complet d'une tâche

```mermaid
sequenceDiagram
    actor U as Utilisateur
    participant O as Orchestrateur
    participant G as Gitea
    participant L as Ollama
    participant S as Sandbox Docker
    participant Q as Quality Gates

    U->>O: POST /api/tasks
    O->>G: git clone
    G-->>O: repository

    O->>L: Planner(requirement + contexte)
    L-->>O: plan.md

    O->>L: Developer(plan + contexte)
    L-->>O: unified diff

    alt dryRun = true
        O->>L: Tester(diff + contexte)
        L-->>O: analyse
        O->>L: Reviewer(plan + diff + analyse)
        L-->>O: revue finale
        O-->>U: Résultat sans modification du repo
    else dryRun = false
        O->>S: création sandbox
        O->>S: git apply --check
        O->>S: git apply
        O->>S: build + tests
        S-->>O: résultats

        O->>Q: Syft + Trivy
        Q-->>O: SBOM + findings

        O->>L: Tester(résultats)
        L-->>O: analyse
        O->>L: Reviewer(evidence)
        L-->>O: revue

        O-->>U: WAITING_APPROVAL
        U->>O: POST /approve
        O->>G: branch + commit + push + PR
        G-->>O: URL Pull Request
        O-->>U: PR créée
    end
```

---

## 9. États de la tâche

```mermaid
stateDiagram-v2
    [*] --> PLANNING
    PLANNING --> GENERATING_PATCH
    GENERATING_PATCH --> APPLYING_PATCH: dryRun=false
    GENERATING_PATCH --> TESTING: dryRun=true
    APPLYING_PATCH --> TESTING
    TESTING --> SECURITY_SCANNING: exécution réelle
    TESTING --> REVIEWING: dryRun
    SECURITY_SCANNING --> REVIEWING
    REVIEWING --> WAITING_APPROVAL: exécution réelle
    REVIEWING --> COMPLETED: dryRun
    WAITING_APPROVAL --> COMPLETED: approve + PR

    PLANNING --> FAILED
    GENERATING_PATCH --> FAILED
    APPLYING_PATCH --> FAILED
    TESTING --> FAILED
    SECURITY_SCANNING --> FAILED
    REVIEWING --> FAILED
```

Les états principaux exposés par l'API sont :

- `PLANNING` ;
- `GENERATING_PATCH` ;
- `APPLYING_PATCH` ;
- `TESTING` ;
- `SECURITY_SCANNING` ;
- `REVIEWING` ;
- `WAITING_APPROVAL` ;
- `COMPLETED` ;
- `FAILED`.

---

## 10. Sandbox Docker

L'agent ne doit pas exécuter directement les commandes de build sur la machine hôte. L'orchestrateur crée donc un container temporaire pour chaque tâche.

```mermaid
flowchart TB
    O[Orchestrateur] --> C[Création container temporaire]
    C --> R[Clone repository]
    R --> A[Application du patch]
    A --> B[Build]
    B --> T[Tests]
    T --> S[Syft + Trivy]
    S --> E[Collecte des résultats]
    E --> D[Destruction du container]
```

Le container de sandbox contient ou utilise :

- Git ;
- Java / Maven ou Gradle selon le projet ;
- Node/npm lorsque nécessaire ;
- Syft ;
- Trivy ;
- un workspace isolé ;
- des credentials temporaires ou limités au POC.

### Limitations de sécurité du MVP

Pour faciliter la création des sandboxes, l'orchestrateur monte le socket Docker :

```yaml
volumes:
  - /var/run/docker.sock:/var/run/docker.sock
```

Ce mécanisme est **acceptable uniquement pour un prototype local dédié**. Il donne à l'orchestrateur un niveau de privilège proche de root sur le moteur Docker.

Dans une cible industrielle, il faut remplacer ce mécanisme par une **Sandbox API**, des **Kubernetes Jobs** ou une technologie d'isolation équivalente.

---

## 11. Contrôles déterministes

La sortie du LLM n'est jamais considérée comme une preuve suffisante de qualité. Le prototype introduit des contrôles reproductibles et indépendants du modèle.

```mermaid
flowchart LR
    PATCH[Patch IA] --> CHECK[git apply --check]
    CHECK --> BUILD[Compile / Build]
    BUILD --> UT[Tests unitaires]
    UT --> IT[Tests intégration]
    IT --> SBOM[Syft SBOM]
    SBOM --> VULN[Trivy]
    VULN --> REVIEW[Review Agent]
    REVIEW --> HUMAN{Approbation humaine}
    HUMAN -->|OK| PR[Pull Request]
```

Le profil `full` ajoute SonarQube et Nexus comme briques supplémentaires, même si leur branchement automatique est volontairement laissé à adapter selon les projets et langages.

---

## 12. SBOM et sécurité

Syft génère un SBOM au format CycloneDX. Trivy analyse les vulnérabilités et les secrets.

```mermaid
flowchart TB
    SRC[Workspace après build]
    SRC --> SYFT[Syft]
    SRC --> TRIVY[Trivy]
    SYFT --> CYC[CycloneDX SBOM]
    TRIVY --> FIND[Findings vulnérabilités / secrets]
    CYC --> EVID[Evidence Bundle]
    FIND --> EVID
    EVID --> REV[Reviewer Agent]
    REV --> HUMAN[Validation humaine]
```

Dans une version industrielle, ce bloc serait étendu avec :

- signature des artefacts ;
- signature du SBOM ;
- provenance SLSA ;
- policy-as-code ;
- vérification de licences ;
- promotion contrôlée Dev → Test → Prod.

---

## 13. Validation humaine

Le prototype ne fait pas d'auto-merge. Après la génération du patch, les tests, les scans et la review, la tâche passe en `WAITING_APPROVAL`.

```mermaid
flowchart LR
    E[Evidence complète] --> W[WAITING_APPROVAL]
    W --> H{Décision humaine}
    H -->|Approve| B[Création branche]
    B --> C[Commit]
    C --> P[Push]
    P --> PR[Pull Request Gitea]
    H -->|Reject| R[Reprise / abandon]
```

Cette étape matérialise le niveau d'autonomie **A2** : l'agent peut produire une PR, mais l'humain garde la décision de merge.

---

## 14. Niveaux d'autonomie visés

```mermaid
flowchart LR
    A0[A0\nConseil uniquement]
    A1[A1\nModifications locales\nHumain valide]
    A2[A2\nCréation de PR\nHumain merge]
    A3[A3\nAuto-merge low-risk\nCI + policies]
    A4[A4\nDéploiement autonome\nCas très maîtrisés]

    A0 --> A1 --> A2 --> A3 --> A4
```

Le prototype est volontairement centré sur **A1/A2**. Une éventuelle ouverture A3 ne doit concerner que des changements faiblement risqués et très déterministes : documentation, génération de tests, refactorings simples ou mises à jour de dépendances encadrées.

---

## 15. Démarrage rapide

### Pré-requis

- Docker Desktop ou Docker Engine ;
- Docker Compose v2 ;
- `make` ;
- `curl` ;
- `git` ;
- `jq` recommandé.

Pour le profil core, 16 Go de RAM peuvent suffire. Pour le profil `full` combinant LLM local, SonarQube, Nexus, Prometheus et Grafana, 24 à 32 Go sont préférables.

### Initialisation

```bash
make init
make up
make model
make bootstrap
```

Services core :

- Gitea : `http://localhost:3000` ;
- Orchestrateur : `http://localhost:8088` ;
- Ollama : `http://localhost:11434`.

Le bootstrap crée un compte POC `aiadmin` ainsi qu'un repository `customer-api`.

---

## 16. Token Gitea

Le script `make bootstrap` tente de générer automatiquement un token Gitea et de le placer dans `.env`.

Si cette étape échoue, créer manuellement un token dans Gitea :

**Settings → Applications → Generate New Token**

Puis renseigner :

```bash
GITEA_TOKEN=...
```

et redémarrer l'orchestrateur :

```bash
docker compose up -d --force-recreate orchestrator
```

---

## 17. Démonstration en dry-run

Le mode `dryRun=true` permet de valider la qualité du raisonnement et la génération du diff sans modifier le repository.

```bash
make demo
curl -s http://localhost:8088/api/tasks | jq
```

Le flux exécuté est :

```mermaid
flowchart LR
    REQ[Requirement] --> PLAN[Planner]
    PLAN --> DEV[Developer]
    DEV --> DIFF[Unified Diff]
    DIFF --> TESTER[Tester]
    TESTER --> REVIEWER[Reviewer]
    REVIEWER --> RESULT[Résultat dry-run]
```

Ce mode est particulièrement utile pour mesurer la fiabilité du modèle sélectionné avant de lui permettre d'appliquer réellement ses modifications.

---

## 18. Exécution complète

Exemple :

```bash
curl -s -X POST http://localhost:8088/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{
    "repositoryUrl":"http://gitea:3000/aiadmin/customer-api.git",
    "baseBranch":"main",
    "requirement":"Add GET /customers/{id}. Return 404 if not found and add tests.",
    "dryRun":false
  }' | jq
```

Suivi de la tâche :

```bash
curl -s http://localhost:8088/api/tasks/<TASK_ID> | jq
```

Lorsque la tâche arrive à `WAITING_APPROVAL` :

```bash
curl -s -X POST http://localhost:8088/api/tasks/<TASK_ID>/approve | jq
```

L'orchestrateur crée alors :

- une branche `ai-factory/<TASK_ID>` ;
- un commit ;
- un push ;
- une Pull Request Gitea.

---

## 19. Profil complet

```bash
make full
```

Le profil `full` ajoute :

- SonarQube : `http://localhost:9000` ;
- Nexus : `http://localhost:8081` ;
- Prometheus : `http://localhost:9090` ;
- Grafana : `http://localhost:3001`.

```mermaid
flowchart TB
    CORE[Core profile\nGitea + Ollama + Orchestrator + Sandboxes]
    FULL[Full profile]
    SONAR[SonarQube]
    NEXUS[Nexus]
    PROM[Prometheus]
    GRAF[Grafana]

    CORE --> FULL
    FULL --> SONAR
    FULL --> NEXUS
    FULL --> PROM
    PROM --> GRAF
```

SonarQube et Nexus sont présents comme briques structurantes de l'usine, mais leur configuration projet, token et repository doit être adaptée au contexte réel.

---

## 20. Choix du modèle Ollama

Le modèle est configuré dans `.env` :

```bash
OLLAMA_MODEL=qwen2.5-coder:7b
```

Après modification :

```bash
make model
docker compose up -d --force-recreate orchestrator
```

Le critère le plus important du POC est la capacité du modèle à produire un **unified diff strict et applicable**. Un taux significatif d'échec à `git apply --check` est une mesure utile : il renseigne directement sur la robustesse du modèle dans un workflow industriel.

---

## 21. API du prototype

### Créer une tâche

`POST /api/tasks`

```json
{
  "repositoryUrl": "http://gitea:3000/aiadmin/customer-api.git",
  "baseBranch": "main",
  "requirement": "Add GET /customers/{id} with 404 and tests",
  "dryRun": true
}
```

### Lire une tâche

`GET /api/tasks/{id}`

### Lister les tâches

`GET /api/tasks`

### Approuver une tâche

`POST /api/tasks/{id}/approve`

---

## 22. Observabilité et KPIs

Le prototype doit permettre de mesurer autre chose que le nombre de tokens ou de lignes de code générées.

```mermaid
flowchart TB
    ORCH[Orchestrateur]
    ACT[Spring Actuator]
    PROM[Prometheus]
    GRAF[Grafana]

    ORCH --> ACT --> PROM --> GRAF

    GRAF --> K1[Taux de réussite des tâches]
    GRAF --> K2[Taux de patchs applicables]
    GRAF --> K3[Taux d'acceptation des PR]
    GRAF --> K4[Durée moyenne d'une tâche]
    GRAF --> K5[Interventions humaines]
    GRAF --> K6[Coût / consommation modèle]
```

KPIs recommandés :

- taux de `git apply --check` réussi ;
- taux de build réussi après génération ;
- taux de tests réussis ;
- nombre de vulnérabilités introduites ;
- taux de PR acceptées ;
- temps moyen de traitement ;
- nombre d'interventions humaines ;
- coût par changement accepté ;
- taux d'échec par rôle agentique.

---

## 23. Limites assumées du MVP

Le prototype est volontairement simple :

- stockage des tâches en mémoire ;
- un seul LLM Ollama ;
- rôles logiques implémentés par prompts ;
- contexte chargé directement depuis le repository ;
- pas de MCP Gateway réel ;
- SonarQube et Nexus non branchés automatiquement à tous les workflows ;
- socket Docker monté dans l'orchestrateur ;
- pas de SSO / Keycloak ;
- pas de queue distribuée ;
- unified diff strict exigé ;
- pas d'auto-merge ;
- validation humaine obligatoire.

Ces limites sont intentionnelles : elles réduisent le nombre de variables à tester pendant la phase de validation.

---

## 24. Sécurité du prototype

### Docker socket

Le socket Docker constitue le principal compromis du POC. Un container qui peut piloter `/var/run/docker.sock` peut généralement obtenir un contrôle étendu sur l'hôte Docker.

### Réseau

L'application du patch peut être exécutée sans réseau. Les builds et scans peuvent rejoindre le réseau local de la factory afin de résoudre les dépendances et d'accéder aux services internes du POC.

Dans une cible d'entreprise, cela doit devenir :

```mermaid
flowchart LR
    SB[Sandbox] --> NP[NetworkPolicy]
    NP --> EGRESS[Egress allow-list]
    EGRESS --> PROXY[Dependency / Artifact Proxy]
    PROXY --> EXT[Sources externes approuvées]
```

### Credentials

Les tokens stockés dans `.env` sont adaptés à un POC local uniquement. La cible doit utiliser :

- identité de workload ;
- tokens à durée de vie courte ;
- Secret Manager / Vault ;
- scopes minimaux.

---

## 25. Trajectoire d'industrialisation

```mermaid
flowchart LR
    P1[Phase 1\nPOC local\nDocker Compose\nA1/A2]
    P2[Phase 2\nSocle industriel\nSSO • DB • Queue\nAI Gateway]
    P3[Phase 3\nContextualisation\nMCP Gateway\nJira • Confluence • CMDB]
    P4[Phase 4\nAgentic Factory\nKubernetes sandboxes\nMulti-agents • A3 low-risk]

    P1 --> P2 --> P3 --> P4
```

### Phase 1 — POC local

- Docker Compose ;
- Gitea ;
- Ollama ;
- Spring Boot Orchestrator ;
- Docker sandboxes ;
- Syft / Trivy ;
- approbation humaine.

### Phase 2 — socle industriel

- PostgreSQL pour persister les tâches ;
- Kafka ou RabbitMQ pour découpler les traitements ;
- SSO / RBAC ;
- AI Gateway multi-modèles ;
- policies centralisées ;
- télémétrie complète.

### Phase 3 — contextualisation

- MCP Gateway privé ;
- Git ;
- Jira ;
- Confluence ;
- CMDB ;
- API Catalog ;
- SonarQube ;
- Nexus / Artifactory.

### Phase 4 — Agentic Factory

- remplacement du socket Docker par Kubernetes Jobs ou Sandbox API ;
- agents spécialisés ;
- modèles différents selon les tâches ;
- workflows multi-agents ;
- niveaux d'autonomie A0 à A3 ;
- supply chain signée ;
- provenance SLSA.

---

## 26. Architecture cible après industrialisation

```mermaid
flowchart TB
    subgraph ENTRY[Product & Engineering]
        JIRA[Jira / Issues]
        CONF[Confluence / ADR]
        BACK[Backstage / Service Catalog]
        USERS[Développeurs / Architectes]
    end

    subgraph CONTROL[AI Software Control Plane]
        PLATFORM[GitLab Duo Agent Platform\nou GitHub AI Controls]
        IAM[SSO / IAM\nOIDC • RBAC • SCIM]
    end

    subgraph MODEL[Agent & Model Plane]
        CODEX[Codex Enterprise]
        CLAUDE[Claude Code]
        CUSTOM[Agents custom]
        AIGW[AI Gateway\nroutage • quotas • DLP • logs]
    end

    subgraph CONTEXT[Context Plane]
        MCPGW[MCP Gateway / Catalogue privé]
        GIT[Git repositories]
        CMDB[CMDB]
        API[API Catalog]
        SONAR[SonarQube]
        ART[Artifact Repository]
    end

    subgraph EXEC[Execution Plane]
        K8S[Kubernetes Jobs / Sandboxes]
        CREDS[Credentials temporaires]
        NET[Egress restreint]
    end

    subgraph ASSURE[Delivery & Assurance]
        CI[CI/CD déterministe]
        SEC[Security Gates]
        SBOM[SBOM]
        SIG[Signature / Provenance]
        REG[Registry]
        PROMO[Promotion Dev → Test → Prod]
    end

    ENTRY --> CONTROL
    CONTROL --> MODEL
    CONTROL --> IAM
    MODEL --> AIGW
    MODEL --> CONTEXT
    MCPGW --> GIT
    MCPGW --> CMDB
    MCPGW --> API
    MCPGW --> SONAR
    MCPGW --> ART
    MODEL --> EXEC
    CONTEXT --> EXEC
    EXEC --> ASSURE
    CI --> SEC --> SBOM --> SIG --> REG --> PROMO
```

---

## 27. Conclusion

Ce prototype permet de tester localement les mécanismes essentiels d'une AI Software Factory sans dépendre immédiatement d'une plateforme SaaS ou d'un environnement Kubernetes complexe.

Il valide notamment :

1. la transformation d'une exigence en plan ;
2. la génération de code sous forme de patch ;
3. l'exécution isolée ;
4. la validation par build et tests ;
5. la génération d'un SBOM ;
6. le scan de vulnérabilités et de secrets ;
7. la revue agentique ;
8. la validation humaine ;
9. la création d'une Pull Request ;
10. la mesure de KPIs utiles à une future industrialisation.

Le POC doit être considéré comme une **miniature fonctionnelle de l'architecture cible**, et non comme une architecture de production. Sa valeur principale est de permettre de mesurer la qualité réelle des agents, la robustesse des workflows, la sécurité des sandboxes et le niveau d'autonomie acceptable avant de généraliser le modèle à l'échelle de l'entreprise.
