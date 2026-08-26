# AI Software Factory Local

Prototype local d'usine logicielle agentique, exécuté avec Docker Compose. Le dépôt matérialise un flux contrôlé de type :

`requirement -> plan -> patch -> validation du diff -> sandbox -> tests -> SBOM -> scan sécurité -> review -> approbation humaine -> pull request`

## Vue d'ensemble

La stack actuelle contient :

| Fonction | Composant |
|---|---|
| Point d'entrée HTTP | `reverse-proxy` Nginx |
| Interface de saisie | `factory-web`, servi par le reverse proxy |
| Orchestration | Spring Boot 3.5 / Java 21 |
| Passerelle LLM | LiteLLM |
| Modèle local | Ollama |
| Modèle cloud optionnel | OpenAI via LiteLLM |
| SCM / PR | Gitea + PostgreSQL |
| Sandbox d'exécution | conteneurs Docker éphémères |
| Tests | Maven / Gradle / npm selon le dépôt |
| SBOM | Syft |
| Scan sécurité | Trivy |
| Observabilité | Prometheus + Grafana |
| Qualité et dépendances | SonarQube + Nexus |

## Ce que fait réellement le prototype

1. L'utilisateur soumet un ticket depuis l'interface web ou `POST /api/tasks`.
2. L'orchestrateur clone le dépôt cible sur la branche demandée.
3. Le `Planner` produit un plan à partir du besoin et du contexte du dépôt.
4. Le `Developer` génère un patch `unified diff`.
5. Le patch est normalisé puis validé avec `git apply --check`.
6. En cas d'échec, un second appel LLM tente une réparation complète du diff.
7. Le patch est appliqué en sandbox, les tests Maven passent par Nexus, SonarQube analyse la qualité, puis les scans sont exécutés.
8. Le `Tester` et le `Reviewer` complètent l'analyse à partir des preuves déterministes.
9. La tâche passe en `WAITING_APPROVAL`.
10. Après `POST /api/tasks/{id}/approve`, l'orchestrateur crée une branche `ai-factory/<taskId>`, committe, pousse et ouvre une PR Gitea.

## Pré-requis

- Docker Desktop ou Docker Engine avec Compose v2
- `make`, `curl`, `git`
- `jq` recommandé pour les appels API
- environ 24 Go de RAM ou plus pour la stack complète avec LLM local

## Démarrage rapide

```bash
make init
make up
make model
make bootstrap
```

URLs principales :

- Factory Web et API publique : `http://localhost:8080`
- Gitea : `http://localhost:3000`
- Orchestrateur direct (diagnostic) : `http://localhost:8088`
- Ollama : `http://localhost:11434`

Le bootstrap crée le compte Gitea de démonstration `aiadmin` et le dépôt `customer-api`, pousse le contenu de `sample-repo/`, puis génère les jetons Gitea et SonarQube manquants dans `.env`.

## Utilisation

### Depuis l'interface web

L'interface `factory-web` est servie par le reverse proxy. Les requêtes du navigateur vers `/api/` sont relayées vers l'orchestrateur ; le navigateur n'accède donc pas directement au port de l'orchestrateur.

L'interface permet de :

- rédiger un ticket structuré ;
- choisir le mode `LOCAL` ou `CLOUD` ;
- suivre l'exécution en temps réel ;
- consulter l'historique des tâches ;
- approuver une tâche en attente.

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

Lire une tâche :

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

Capacités exposées :

```bash
curl -s http://localhost:8080/api/capabilities
```

## États de tâche

Les statuts réellement utilisés sont :

- `QUEUED`
- `CLONING`
- `PLANNING`
- `GENERATING_PATCH`
- `APPLYING_PATCH`
- `TESTING`
- `QUALITY_SCANNING`
- `SECURITY_SCANNING`
- `REVIEWING`
- `WAITING_APPROVAL`
- `APPROVED`
- `PR_CREATED`
- `FAILED`

`/api/tasks/{id}` retourne aussi le plan, le patch, les résumés de test et de sécurité, la review IA, l'erreur éventuelle, l'URL de PR et l'historique des étapes.

## Modes LLM

Le routage LLM passe toujours par LiteLLM :

- `LOCAL` -> alias `factory-code-local` -> Ollama
- `CLOUD` -> alias `factory-code-cloud` -> OpenAI

Le mode cloud n'est accepté que si `AI_FACTORY_CLOUD_ENABLED=true`.

Variables `.env` principales :

```bash
OLLAMA_MODEL=qwen2.5-coder:7b
OPENAI_MODEL=gpt-5.6-luna
OPENAI_API_KEY=
AI_FACTORY_CLOUD_ENABLED=false
LITELLM_MASTER_KEY=local-dev-litellm-key
```

## Démonstration

Envoyer une tâche de démonstration :

```bash
make demo
```

Exécuter le flux complet avec patch appliqué, tests et scans :

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

## Qualité et observabilité

`make up` démarre aussi :

- SonarQube : `http://localhost:9000`
- Nexus : `http://localhost:8081`
- Prometheus : `http://localhost:9090`
- Grafana : `http://localhost:3001`

Nexus est le miroir Maven utilisé par les builds Maven du sandbox. SonarQube analyse les dépôts Maven en exécution complète lorsque `SONAR_TOKEN` est renseigné. `make bootstrap` génère ce jeton automatiquement s’il manque, à partir de `SONAR_ADMIN_LOGIN` et `SONAR_ADMIN_PASSWORD`, puis recrée l’orchestrateur. Sans jeton, l’étape de qualité est explicitement ignorée ; les autres contrôles restent exécutés.

## Commandes utiles

```bash
make help
make status
make logs
make restart
make urls
make test
make down
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
