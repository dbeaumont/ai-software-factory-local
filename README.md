# AI Software Factory — prototype local Docker

Prototype simplifié d'une usine logicielle agentique d'entreprise. Il montre le flux **requirement → plan → patch → sandbox → tests → SBOM/vuln scan → review → approbation humaine → PR**.

## 1. Composants

| Fonction | Composant |
|---|---|
| SCM / PR | Gitea |
| LLM local | Ollama |
| Gateway LLM | LiteLLM (sélection locale ou cloud) |
| Saisie des spécifications | Factory Web (ticket d'usine) |
| Orchestration | Spring Boot 3.5 / Java 21 |
| Sandbox | Docker containers éphémères |
| Tests | Maven / Gradle / npm (détection simple) |
| SBOM | Syft / CycloneDX |
| Vulnérabilités / secrets | Trivy |
| Qualité | SonarQube (profil `full`, à raccorder au pipeline selon le projet) |
| Artefacts | Nexus Repository (profil `full`) |
| Métriques | Spring Actuator + Prometheus + Grafana (profil `full`) |

## 2. Pré-requis

- Docker Desktop / Docker Engine + Docker Compose v2
- `make`, `curl`, `git`, `jq` recommandés sur l'hôte
- 16 Go RAM minimum pour le profil core ; 24–32 Go recommandés pour `full` + LLM local

> Le premier build télécharge les images Docker ainsi que Syft/Trivy dans l'image de sandbox.

## 3. Démarrage rapide

```bash
make init
make up
make model
make bootstrap
```

Services core :
- Factory Web : `http://localhost:8080`
- Gitea : `http://localhost:3000`
- Orchestrateur : `http://localhost:8088`
- Ollama : `http://localhost:11434`

Le script `bootstrap` crée le compte POC `aiadmin` (mot de passe dans `.env`) et le dépôt `customer-api`.

### Saisir une spécification depuis l'interface

Ouvrez `http://localhost:8080`. L'interface présente un ticket avec un résumé, le contexte, les critères d'acceptation, le dépôt cible et la branche. À l'envoi, elle crée une tâche dans l'orchestrateur et suit son état automatiquement. Le mode simulation est activé par défaut pour éviter toute modification du dépôt.

### Choisir le LLM du ticket

Le slider **LLM local / LLM cloud** est une décision explicite stockée avec la tâche. Il n'y a pas de fallback automatique : le mode sélectionné est utilisé pour toutes les étapes agentiques du ticket.

- **LLM local** utilise Ollama ; le code et la spécification restent dans les conteneurs locaux.
- **LLM cloud** utilise OpenAI via LiteLLM ; le contexte du dépôt et la spécification sont transmis au fournisseur cloud.

Le cloud est désactivé par défaut. Pour l'autoriser, renseignez `.env` puis relancez la stack :

```bash
OPENAI_API_KEY=...
AI_FACTORY_CLOUD_ENABLED=true
```

La clé OpenAI est injectée uniquement dans LiteLLM, jamais dans le navigateur ni dans l'orchestrateur.

### Générer un token Gitea

Le script `make bootstrap` tente de générer automatiquement un token Gitea et de l’écrire dans `.env`. Si cela échoue, dans Gitea : **Settings → Applications → Generate New Token** puis placez la valeur dans :

```bash
GITEA_TOKEN=...
```

puis redémarrez l'orchestrateur :

```bash
docker compose up -d --force-recreate orchestrator
```

## 4. Première démonstration en dry-run

```bash
make demo
curl -s http://localhost:8088/api/tasks | jq
```

Le dry-run exécute Planner + Developer + Tester + Reviewer mais **n'applique pas le patch** et ne lance pas les quality gates. C'est le meilleur mode pour valider la génération du diff avec le modèle choisi.

## 5. Exécution complète

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

Suivre la tâche :

```bash
curl -s http://localhost:8088/api/tasks/<TASK_ID> | jq
```

États principaux : `PLANNING`, `GENERATING_PATCH`, `APPLYING_PATCH`, `TESTING`, `SECURITY_SCANNING`, `REVIEWING`, `WAITING_APPROVAL`.

Chaque patch est d'abord validé avec `git apply --check` dans le sandbox. En cas de diff invalide, l'usine demande une réécriture complète au modèle avec le diagnostic Git, puis valide cette seconde version avant toute modification du dépôt. Si elle est encore invalide, la tâche passe en `FAILED` et conserve `changes.patch` ainsi que `changes.invalid.patch` dans le volume `factory-workspace`. Les sandboxes remontent ce volume Docker nommé directement, ce qui évite les restrictions de partage de chemins de Docker Desktop sur macOS. C'est volontaire : le prototype ne masque pas les échecs agentiques.

### Approbation humaine et création de PR

Quand la tâche est `WAITING_APPROVAL` :

```bash
curl -s -X POST http://localhost:8088/api/tasks/<TASK_ID>/approve | jq
```

L'orchestrateur crée alors une branche `ai-factory/<TASK_ID>`, committe, pousse et ouvre une Pull Request Gitea.

## 6. Profil complet

```bash
make full
```

Ajoute :
- SonarQube : `http://localhost:9000`
- Nexus : `http://localhost:8081`
- Prometheus : `http://localhost:9090`
- Grafana : `http://localhost:3001`

SonarQube et Nexus sont volontairement **présents comme briques d'usine** mais leur configuration projet/token/repository reste à adapter au langage et au contexte du POC. Syft + Trivy sont, eux, intégrés directement dans le flux d'exécution.

## 7. Choisir le modèle Ollama

Modifier `.env` :

```bash
OLLAMA_MODEL=qwen2.5-coder:7b
```

Puis :

```bash
make model
docker compose up -d --force-recreate litellm orchestrator
```

Pour un POC agentique, privilégier un modèle de code suffisamment fiable pour produire des unified diffs. Un modèle trop petit échouera souvent à `git apply --check` — ce taux d'échec est justement un KPI utile du prototype.

## 8. API

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

### Lister

`GET /api/tasks`

### Approuver

`POST /api/tasks/{id}/approve`

## 9. Limites assumées du MVP

- stockage des tâches en mémoire ;
- un seul LLM Ollama, rôles logiques via prompts ;
- pas encore de MCP réel : le contexte est chargé directement depuis le repository ;
- SonarQube/Nexus ne sont pas encore branchés automatiquement au pipeline ;
- Docker socket monté dans l'orchestrateur (POC uniquement) ;
- pas de SSO/Keycloak dans le MVP ;
- le patch LLM doit être un unified diff strict et peut échouer ;
- pas d'auto-merge : l'approbation humaine reste obligatoire.

## 10. Étapes d'industrialisation

1. Remplacer le Docker socket par des Kubernetes Jobs / Sandbox API.
2. Ajouter PostgreSQL pour l'état des tâches et une queue (Kafka/RabbitMQ).
3. Introduire un AI Gateway multi-modèles.
4. Ajouter un MCP Gateway privé et des serveurs MCP approuvés (Git, Jira, Confluence, Sonar, Artifactory/Nexus).
5. Ajouter Keycloak/SSO/RBAC et policy-as-code.
6. Brancher SonarQube et Nexus dans les quality gates.
7. Signer artefacts/SBOM et produire des attestations SLSA.
8. Ajouter des niveaux d'autonomie A0–A3 par type de changement.

Voir `docs/architecture.md` et `docs/security.md`.

## Documentation détaillée

Le dossier complet d'explication du prototype, avec les diagrammes au format Mermaid, est disponible dans :

`docs/AI_SOFTWARE_FACTORY_LOCAL.md`
