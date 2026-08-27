# AI Software Factory

Prototype local d'usine logicielle agentique, exécuté avec Docker Compose. Le dépôt matérialise un flux contrôlé de type :

`requirement -> plan -> patch -> validation du diff -> réparation si besoin -> sandbox -> tests -> SonarQube -> SBOM Syft -> scan Trivy -> review IA -> approbation humaine -> pull request Gitea`

## Organisation du dépôt

| Répertoire | Contenu |
|---|---|
| `apps/` | Applications exécutables : orchestrateur et interface web |
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
| Orchestration | Spring Boot 3.5 / Java 21 (`orchestrator`) |
| Passerelle LLM | LiteLLM (port 4000 interne) |
| Modèle local | Ollama (`qwen2.5-coder:7b` par défaut) |
| Modèle cloud optionnel | OpenAI via LiteLLM (`gpt-5.6` configurable) |
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
9. Les tests unitaires/d'intégration s'exécutent dans la sandbox (via Artifactory pour Maven). L'agent `Tester` analyse les journaux de test.
10. L'analyse de qualité SonarQube est déclenchée (pour les projets Maven quand `SONAR_TOKEN` est présent).
11. Syft génère un SBOM CycloneDX (`.ai-factory/sbom.cdx.json`) et Trivy scanne les vulnérabilités/secrets (`.ai-factory/trivy.txt`).
12. L'agent `Reviewer` synthétise l'ensemble des preuves déterministes (plan, patch, tests, qualité, sécurité) dans `.ai-review.md`.
13. La tâche passe au statut `WAITING_APPROVAL`.
14. Après approbation humaine (`POST /api/tasks/{id}/approve`), l'orchestrateur bascule sur une branche `ai-factory/<taskId>`, exclut les artefacts de travail IA (`git reset`), committe, pousse vers Gitea et ouvre une Pull Request.

## Pré-requis

- Docker Desktop ou Docker Engine avec Compose v2
- `make`, `curl`, `git`, `bash`
- Python 3 (pour les scripts de bootstrap)
- `jq` recommandé pour manipuler les réponses API
- Environ 24 Go de RAM recommandés pour la stack complète avec LLM local

## Démarrage rapide

```bash
make init
make up
make model
make bootstrap
```

URLs principales :

- Interface Web & API publique : `http://localhost:8080`
- Gitea : `http://localhost:3000` (dépôt démo : `http://localhost:3000/aiadmin/customer-api`)
- Orchestrateur direct (diagnostic & Actuator) : `http://localhost:8088`
- SonarQube : `http://localhost:9000`
- Artifactory : `http://localhost:8082` (utilisateur `admin`, mot de passe `password`)
- Prometheus : `http://localhost:9090`
- Grafana : `http://localhost:3001`
- Ollama API : `http://localhost:11434`

Le script `make bootstrap` initialise le compte Gitea `aiadmin`, le compte reviewer `reviewer`, le dépôt de démonstration `customer-api`, pousse le contenu de `examples/customer-api/`, et génère automatiquement les jetons `GITEA_TOKEN` et `SONAR_TOKEN` dans le fichier `.env`.

## Utilisation

### Depuis l'interface web

L'interface `factory-web` est servie par le reverse proxy Nginx. Les appels API vers `/api/` sont redirigés de manière transparente vers l'orchestrateur.

L'interface permet de :

- rédiger un ticket structuré (résumé, objectif métier, périmètre, comportement actuel/attendu, critères d'acceptation) ;
- utiliser le bouton de pré-remplissage de démo ("Charger le modèle de ticket") ;
- choisir le mode `LOCAL` (Ollama) ou `CLOUD` (OpenAI via LiteLLM) ;
- suivre la progression en temps réel (stepper, logs, progression) ;
- consulter l'historique complet des exécutions (vue "Exécutions") ;
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
    "llmMode":"LOCAL"
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

## Modes LLM

Le routage des modèles s'effectue via LiteLLM :

- `LOCAL` -> modèle `factory-code-local` -> Ollama (`qwen2.5-coder:7b`)
- `CLOUD` -> modèle `factory-code-cloud` -> OpenAI (`gpt-5.6`)

Le mode cloud n'est accessible que si `AI_FACTORY_CLOUD_ENABLED=true` dans `.env`.

Variables de configuration principales :

```bash
OLLAMA_MODEL=qwen2.5-coder:7b
OPENAI_MODEL=gpt-5.6
AI_FACTORY_CLOUD_ENABLED=false
LITELLM_MASTER_KEY=local-dev-litellm-key
```

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
- **Prometheus** (`http://localhost:9090`) : Collecte les métriques Micrometer depuis `/actuator/prometheus` de l'orchestrator (`ai_factory_tasks_submitted`, `ai_factory_tasks_completed`, `ai_factory_tasks_failed`).
- **Grafana** (`http://localhost:3001`) : Tableau de bord de suivi pré-provisionné (`orchestrator.json`).

## Commandes Make disponibles

| Commande | Description |
|---|---|
| `make help` | Affiche l'aide des commandes Make |
| `make init` | Initialise `.env` et `.vault` à partir des exemples |
| `make build` | Construit l'image sandbox et les services Compose |
| `make up` | Démarre la stack complète en arrière-plan |
| `make all` | Remet à zéro les données et démarre une stack entièrement bootstrappée |
| `make model` | Télécharge le modèle Ollama configuré |
| `make bootstrap` | Initialise Gitea, SonarQube et génère les jetons d'accès |
| `make tokens` | Régénère ou valide les jetons Gitea et SonarQube |
| `make demo` | Soumet une tâche de démo à l'orchestrateur |
| `make test` | Exécute les tests unitaires de l'orchestrateur |
| `make package` | Compile et empaquette l'orchestrateur Java (sans tests) |
| `make config` | Valide et affiche la configuration Compose |
| `make status` | Affiche l'état des conteneurs |
| `make restart` | Redémarre l'orchestrateur |
| `make logs` | Suit les journaux de l'orchestrateur |
| `make urls` | Liste toutes les URLs de services et points d'accès |
| `make down` | Arrête la stack Compose |
| `make clean` | Arrête la stack et supprime tous les volumes (destructif) |
make clean
```

## Limites actuelles

- stockage des tâches en mémoire uniquement ;
- pas de persistance PostgreSQL pour l'orchestrateur ;
- exécution locale uniquement, sans scheduler ni queue ;
- prompts versionnés mais sans gouvernance avancée ;
- un seul pipeline d'exécution, avec rôles LLM logiques ;
- support des builds limité à Maven, Gradle et npm ;
- pas de SSO, RBAC ni policy engine ;
- montage de `/var/run/docker.sock` dans l'orchestrateur ;
- pas de sandbox Kubernetes ni d'egress allow-list ;
- approbation humaine obligatoire avant push/PR ;

## Documentation complémentaire

- [Vue détaillée du prototype](docs/AI_SOFTWARE_FACTORY_LOCAL.md)
- [Architecture](docs/architecture.md)
- [Sécurité](docs/security.md)
