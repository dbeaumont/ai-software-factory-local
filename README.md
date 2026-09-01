# AI Software Factory

Prototype local d'usine logicielle agentique, exécuté avec Docker Compose. Le dépôt matérialise un flux contrôlé de type :

`requirement -> plan -> patch -> validation du diff -> réparation si besoin -> sandbox -> tests -> SonarQube -> SBOM Syft -> scan Trivy -> review IA -> approbation humaine -> pull request Gitea`

## Organisation du dépôt

| Répertoire | Contenu |
|---|---|
| `apps/` | Applications exécutables : orchestrateur, serveurs MCP et interface web |
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
| Observabilité | Prometheus v3.5 + Grafana 12.1 (métriques Micrometer/Actuator) |

## Ce que fait réellement le prototype

1. L'utilisateur soumet un ticket depuis l'interface web (`factory-web`) ou via l'API REST `POST /api/tasks`.
2. L'orchestrateur attribue une référence unique (`AF-0001`, etc.) et clone le dépôt cible de manière asynchrone.
3. Le service de contexte extrait la structure et le contenu du projet.
4. L'agent `Planner` produit une feuille de route (`.ai-plan.md`).
5. L'agent `Developer` génère un patch `unified diff`.
6. Le patch est normalisé (`UnifiedDiffNormalizer`), puis validé avec `git apply --check` dans une sandbox sans réseau.
7. En cas d'échec de validation du diff, l'agent `PatchRepair` tente une réparation complète en analysant les fichiers sources authoritative.
8. Le patch est appliqué en sandbox, puis `git diff --check` et `git diff --stat` sont contrôlés.
9. Les tests unitaires/d'intégration s'exécutent dans la sandbox (via Artifactory pour Maven). L'agent `Tester` analyse les journaux de test avec un contrat JSON validé.
10. L'analyse de qualité SonarQube est déclenchée ; son quality gate est bloquant. En l'absence de jeton ou pour un type de projet non encore pris en charge, le run échoue au lieu de considérer le contrôle comme réussi.
11. Syft génère un SBOM CycloneDX (`.ai-factory/sbom.cdx.json`) et Trivy scanne les vulnérabilités/secrets (`.ai-factory/trivy.txt`) ; une détection HIGH ou CRITICAL est bloquante.
12. L'agent `Reviewer` synthétise les preuves dans `.ai-review.md` avec un contrat JSON validé. Un rejet ou un finding `blocker` bloque le run.
13. La tâche passe au statut `WAITING_APPROVAL`.
14. Après approbation humaine (`POST /api/tasks/{id}/approve`), l'orchestrateur bascule sur une branche `ai-factory/<taskId>`, exclut les artefacts de travail IA (`git reset`), committe, pousse vers Gitea et ouvre une Pull Request.

## Pré-requis

- Docker Desktop ou Docker Engine avec Compose v2
- `make`, `curl`, `git`, `bash`
- JDK 25 et Maven 3.6.3+ pour compiler ou tester les modules Java hors Docker (les images Docker embarquent déjà Temurin 25)
- Python 3 (pour les scripts de bootstrap)
- `jq` recommandé pour manipuler les réponses API
- Environ 16 Go de RAM recommandés pour la stack complète

## Démarrage rapide

```bash
make init
make up
make bootstrap
```

URLs principales :

- Interface Web & API publique : `http://localhost:8080`
- Gitea : `http://localhost:3000` (dépôts de démonstration : `customer-api`, `inventory-gradle`, `checkout-node`)
- Orchestrateur direct (diagnostic & Actuator) : `http://localhost:8088`
- SonarQube : `http://localhost:9000`
- Artifactory : `http://localhost:8082` (utilisateur `admin`, mot de passe `password`)
- Prometheus : `http://localhost:9090`
- Grafana : `http://localhost:3001`

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

Les 13 statuts du cycle de vie d'une tâche sont :

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
13. `FAILED` : Échec rencontré à l'une des étapes (diff invalide non réparable, erreur de build, etc.).

## Modèle LLM

Tous les appels passent par LiteLLM vers le modèle cloud `factory-code-cloud` (`gpt-5.6-luna` par défaut).

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
Les valeurs d'environnement configurées côté serveur doivent être monolignes (CR/LF/NUL refusés) et sont transmises
par un fichier `--env-file`, jamais concaténées au script. Les noms de fichiers et le contenu du patch sont uniquement
lus par Git : les métacaractères shell qu'ils contiennent ne sont pas évalués.

```bash
AI_FACTORY_MCP_CLIENT_ENABLED=true
AI_FACTORY_MCP_SANDBOX_ENABLED=true
AI_FACTORY_MCP_SANDBOX_MODE=MCP_ACTIVE
AI_FACTORY_MCP_SANDBOX_ACTIVE_OPERATIONS=validate_patch,apply_patch,run_tests,run_quality,run_security
```

Le chemin Docker historique de l'orchestrateur a été supprimé. Une opération absente de la liste, un serveur MCP
désactivé ou un ancien mode `DIRECT`/`MCP_SHADOW` échoue fermé, sans réintroduire la socket ou les secrets.

Le contrôleur reste une solution locale POC-only : lui seul monte encore `/var/run/docker.sock`. Son conteneur est
non-root, read-only, sans capabilities, sans port hôte et attaché uniquement au réseau MCP interne. En production,
ce backend devra être remplacé par des Jobs Kubernetes ou une Sandbox API sans modifier les outils MCP.

Les états bornés et déjà redacted des jobs sont écrits atomiquement dans le volume dédié `sandbox-job-state`.
Après un redémarrage, les résultats terminaux et les clés d'idempotence sont restaurés ; toute exécution qui était
encore active devient `FAILED / INDETERMINATE`, et seuls les conteneurs `ai-factory-sbx-<execution_id>` sont nettoyés.
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
NPM_REGISTRY_URL=
NPM_REGISTRY_HOST=
```

Sur un poste disposant d'un accès Internet direct, laissez les trois variables de registre ci-dessus vides :
Maven utilise alors Maven Central et npm son registre public par défaut. En environnement d'entreprise,
renseignez-les avec les endpoints autorisés ; le `settings.xml` et le jeton Artifactory ne sont chargés que
lorsqu'un miroir Maven est explicitement configuré.

Pour utiliser le mode cloud, placez votre clé OpenAI dans le fichier `.vault` :
```bash
VAULT_OPENAI_API_KEY=sk-...
```
Ce fichier est exclu du contrôle de version Git et est chargé de façon sécurisée par le conteneur LiteLLM.

Si un proxy d'entreprise intercepte le trafic HTTPS et présente un certificat interne, LiteLLM ajoute au démarrage la chaîne présentée par `api.openai.com:443` à son bundle de confiance. Pour employer un autre endpoint, configurez dans `.env` :
```bash
OPENAI_CA_CERT_HOST=api.openai.com:443
```
Cette étape n'est exécutée que lorsqu'une clé OpenAI est configurée et la vérification TLS reste active.
Les certificats d'interception historiques qui n'ont pas d'Authority Key Identifier restent compatibles avec Python 3.13 ; la chaîne, la signature et le nom d'hôte restent vérifiés.

## Démonstration

Lancer une tâche de démo pré-configurée :

```bash
make demo
```

## Qualité et observabilité

- **SonarQube** (`http://localhost:9000`) : Analyse de la qualité du code Java/Maven. Les jetons sont générés par `make bootstrap` ou `make tokens`.
- **Artifactory** (`http://localhost:8082`) : Dépôt d'artefacts local. Les builds Maven des sandboxes utilisent le miroir explicite `MAVEN_MIRROR_URL`.
- **Prometheus** (`http://localhost:9090`) : Collecte les métriques Micrometer de l'orchestrateur et des serveurs MCP, dont les appels clients et les compteurs de jobs sandbox.
- **Grafana** (`http://localhost:3001`) : Tableau de bord de suivi pré-provisionné (`orchestrator.json`).

## Commandes Make disponibles

| Commande | Description |
|---|---|
| `make help` | Affiche l'aide des commandes Make |
| `make init` | Initialise `.env` et `.vault` à partir des exemples |
| `make build` | Construit l'image sandbox et les services Compose |
| `make up` | Démarre la stack complète en arrière-plan |
| `make all` | Remet à zéro les données et démarre une stack entièrement bootstrappée |
| `make bootstrap` | Initialise Gitea, SonarQube et génère les jetons d'accès |
| `make tokens` | Régénère ou valide les jetons Gitea et SonarQube |
| `make demo` | Soumet une tâche de démo à l'orchestrateur |
| `make test` | Exécute les tests de l'orchestrateur et des serveurs MCP |
| `make test-sandbox-runtime` | Vérifie les contraintes effectives des conteneurs sandbox |
| `make mcp-shadow-campaign` | Valide le corpus shadow de 20 tâches sans l'exécuter |
| `make mcp-shadow-campaign CAMPAIGN_ARGS=--execute AI_FACTORY_RUN_CLOUD_CAMPAIGN=true` | Exécute volontairement la campagne cloud séquentielle |
| `make mcp-shadow-report` | Génère le rapport des métriques shadow courantes |
| `make package` | Compile et empaquette l'orchestrateur Java (sans tests) |
| `make config` | Valide et affiche la configuration Compose |
| `make status` | Affiche l'état des conteneurs |
| `make restart` | Redémarre l'orchestrateur |
| `make logs` | Suit les journaux de l'orchestrateur |
| `make urls` | Liste toutes les URLs de services et points d'accès |
| `make down` | Arrête la stack Compose |
| `make clean` | Arrête la stack et supprime tous les volumes (destructif) |
```

## Limites actuelles

- stockage des tâches en mémoire uniquement ;
- pas de persistance PostgreSQL pour l'orchestrateur ;
- exécution locale avec file bornée en mémoire, sans scheduler distribué ;
- prompts versionnés mais sans gouvernance avancée ;
- un seul pipeline d'exécution, avec rôles LLM logiques ;
- support des builds limité à Maven, Gradle et npm ;
- pas de SSO, RBAC ni policy engine ;
- montage de `/var/run/docker.sock` encore présent dans le contrôleur local `sandbox-execution-mcp` ;
- pas de sandbox Kubernetes ni d'egress allow-list ;
- approbation humaine obligatoire avant push/PR ;

Les règles de confiance des prompts, la validation des contrats de sortie et les gates de tests, qualité et
sécurité ont été renforcés dans le prototype. Ils ne remplacent pas le SSO/RBAC, un moteur de policy-as-code,
ni une sandbox de production : ces limites restent bloquantes pour un usage entreprise exposé.

## Documentation complémentaire

- [Fonctionnement et workflow](docs/proto-workflow.md)
- [Architecture](docs/proto-architecture.md)
- [Sécurité](docs/proto-security.md)
