# MCP-003 — Secrets, volumes, réseaux, identités et destinations

> Statut : validé pour le prototype Compose  
> Périmètre observé : `.env.example`, `infrastructure/compose.yaml`, configurations Spring et profils sandbox  
> Valeurs sensibles : volontairement exclues de ce document

## 1. Objet et méthode

Ce document établit la baseline des dépendances techniques utilisées par chaque capacité avant la bascule MCP complète. Il inventorie les noms et propriétaires des secrets, jamais leur valeur. Il distingue :

- le déploiement Compose actif ;
- les implémentations directes conservées temporairement pour `DIRECT`/`MCP_SHADOW` ;
- la propriété attendue dans la cible MCP puis GCP.

Les fichiers locaux `.env` et `.vault` ne sont pas lus ni reproduits. Les noms proviennent des exemples et des références de configuration versionnées.

## 2. Vue par capacité

| Capacité | Composant actuel | Secret runtime | Stockage/volume | Réseau et destinations | Propriétaire cible |
|---|---|---|---|---|---|
| Orchestration | `orchestrator` | `LITELLM_MASTER_KEY`, encore `GITEA_TOKEN` | `factory-workspace` en lecture/écriture, prompts en lecture seule | `factory` vers LiteLLM et encore Gitea ; `mcp-internal` vers les serveurs MCP | `sa-orchestrator` ; aucun secret Gitea/Sonar/Artifactory/Docker |
| Inférence | `litellm`, fournisseur cloud | `LITELLM_MASTER_KEY`, `VAULT_OPENAI_API_KEY`/`OPENAI_API_KEY` | aucun volume de modèle | egress TLS vers le fournisseur cloud | identité LiteLLM dédiée, secrets fournisseur dans Secret Manager |
| Contexte dépôt | `RepositoryContextService` ou `repository-context-mcp` | aucun secret runtime | `factory-workspace:/workspace/tasks:ro` pour MCP | uniquement `mcp-internal`; aucun egress attendu | `sa-repository-context-mcp`, lecture limitée au workspace de la tentative |
| Validation/application de patch | `sandbox-execution-mcp` et conteneur sandbox | aucun secret fonctionnel | workspace partagé ; `sandbox-job-state`; socket Docker local | MCP sur `mcp-internal`; job avec réseau `none` | `sa-sandbox-controller` puis identité éphémère de tentative GKE |
| Tests | jobs sandbox `test-maven-v1`, `test-gradle-v1`, `test-node-v1` | `ARTIFACTORY_TOKEN` si miroir authentifié | workspace ; cache `ai-factory-m2` réservé à Maven | réseau interne `sandbox-egress`; Artifactory direct et domaines Gradle/npm via proxy allow-listé | contrôleur sandbox/identité de job, secret à durée courte |
| Qualité | job sandbox `quality-sonar-v1` | `SONAR_TOKEN`, éventuellement `ARTIFACTORY_TOKEN` | workspace, cache Maven, rapports `.ai-factory` | réseau interne `sandbox-egress`; SonarQube et Artifactory seulement en accès direct | contrôleur sandbox pour produire le rapport ; `assurance-mcp` pour le verdict |
| Sécurité | job sandbox `security-syft-trivy-v1` | aucun dans le profil actuel | workspace, SBOM et rapport `.ai-factory` | réseau interne `sandbox-egress`; la cible reste un scan sans réseau avec bases préchargées | identité de job sandbox ; mises à jour de bases via pipeline séparé |
| Livraison SCM | `scm-delivery-mcp` | `GITEA_TOKEN` et nom `AI_FACTORY_GITEA_USER`, confinés au serveur | workspace source read-only puis staging privé | serveur SCM vers `gitea:3000` et push HTTP authentifié | `sa-scm-delivery-mcp`, jeton Gitea dédié jusqu'à fédération d'identité |
| Assurance | pas encore séparée ; décision minimale dans l'orchestrateur | futur jeton Sonar en lecture si interrogation API | futurs rapports référencés par digest | SonarQube et `evidence-mcp` seulement | `sa-assurance-mcp`, lecture seule |
| Preuves | fichiers locaux du workspace | aucun actuellement | `factory-workspace`; aucun stockage immuable | aucun backend dédié | `sa-evidence-mcp`, puis bucket Cloud Storage dédié et KMS si signature |
| Observabilité | Prometheus/Grafana | comptes administrateur Grafana hors flux MCP | `grafana-data`, configuration Prometheus en lecture seule | Prometheus joint `factory` et `mcp-internal` pour scrapper les endpoints Actuator | identité de collecte dédiée, accès métriques uniquement |

## 3. Inventaire des secrets

### 3.1 Secrets fonctionnels et de plateforme

| Secret/configuration sensible | Détenteur Compose actuel | Usage actuel | Détenteur cible | Action de bascule |
|---|---|---|---|---|
| `LITELLM_MASTER_KEY` | LiteLLM et orchestrateur | authentification de la passerelle de modèles | LiteLLM ; l'orchestrateur utilise une identité/audience dédiée | Remplacer la clé partagée statique par une identité de workload ou un jeton court. Hors migration des outils MCP. |
| `VAULT_OPENAI_API_KEY` / `OPENAI_API_KEY` | LiteLLM via `.vault` | fournisseur de modèle cloud | LiteLLM uniquement | Stocker dans Secret Manager, interdire toute propagation à un agent ou serveur MCP. |
| `GITEA_TOKEN` | `scm-delivery-mcp` uniquement | commit, push et création de PR | `scm-delivery-mcp` uniquement | Retiré de l'orchestrateur à MCP-123 ; scope dépôt/PR minimal, rotation et révocation à tester. |
| `SONAR_TOKEN` injecté comme `AI_FACTORY_SONAR_TOKEN` | `sandbox-execution-mcp`, puis fichier d'environnement temporaire du job qualité | lancement du scanner et attente du quality gate | contrôleur sandbox pour le scan ; jeton distinct en lecture pour Assurance si nécessaire | Secret Manager, fichiers temporaires `0600`, suppression après job et redaction systématique. |
| `ARTIFACTORY_TOKEN` | BuildKit au build ; `sandbox-execution-mcp`, puis jobs tests/qualité | téléchargement de dépendances | pipeline de build et contrôleur sandbox, avec identités/scopes distincts | Ne jamais le remettre dans l'orchestrateur ; limiter aux dépôts virtuels en lecture. |
| `ARTIFACTORY_DB_PASSWORD` | Artifactory et sa base | connexion PostgreSQL | plateforme Artifactory | Secret de plateforme, sans exposition MCP. |
| `JF_SHARED_SECURITY_MASTERKEY`, `JF_SHARED_SECURITY_JOINKEY` | Artifactory via `.vault` | chiffrement/adhésion du service | plateforme Artifactory | Conservation stable, sauvegarde et rotation selon procédure JFrog ; hors appels MCP. |
| `GITEA_DB_PASSWORD` | Gitea et `gitea-db` | connexion PostgreSQL | plateforme SCM | Secret de plateforme, sans exposition MCP. |
| `GITEA_ADMIN_PASSWORD`, `GITEA_REVIEWER_PASSWORD` | scripts/bootstrap local | comptes POC | IAM/SSO et comptes nominatifs | Ne pas réutiliser le compte administrateur comme compte de service SCM. |
| `SONAR_ADMIN_PASSWORD` | bootstrap local | initialisation SonarQube | administration SonarQube | Le scanner et Assurance n'utilisent pas le compte administrateur. |
| `GRAFANA_ADMIN_PASSWORD` | Grafana | administration locale | plateforme observabilité/SSO | Hors périmètre des serveurs MCP. |

### 3.2 Constats de sécurité

- Les valeurs d'exemple de certains comptes locaux sont faibles et réservées au POC ; elles ne constituent pas une cible partagée.
- Les deux serveurs MCP actifs n'authentifient pas encore l'appelant par identité applicative. Le réseau `mcp-internal` réduit l'exposition mais ne remplace pas issuer, audience, scopes et autorisation par outil.
- Le jeton Gitea reste le principal secret métier encore détenu par l'orchestrateur.
- Le contrôleur sandbox reçoit correctement les secrets Sonar/Artifactory côté serveur. Ils ne sont injectés que dans les profils qui les déclarent, mais restent des variables d'environnement statiques dans Compose.
- Le nom d'une variable, une URL interne, un nom de volume ou un profil ne doit jamais être modifiable par une sortie de modèle.

## 4. Inventaire des volumes et systèmes de fichiers

| Volume/montage | Producteurs/consommateurs | Droits actuels | Risque ou usage | Cible |
|---|---|---|---|---|
| `factory-workspace` | orchestrateur RW ; `repository-context-mcp` RO ; contrôleur sandbox RO ; jobs sandbox RO ou RW selon profil | partagé entre tâches par nom de sous-répertoire | frontière interservices fondée sur un chemin local et isolation dépendante de `task_id` | espace par `task_id` + `attempt_id`, handle opaque, source figée et artefacts par digest |
| `sandbox-job-state` | `sandbox-execution-mcp` | RW | persistance locale des handles et heartbeats | stockage durable/idempotent du contrôleur ; Cloud SQL/Firestore selon choix d'architecture |
| `/var/run/docker.sock` | `sandbox-execution-mcp` seulement | accès au daemon via groupe `DOCKER_SOCKET_GID` | équivaut à un privilège hôte élevé | suppression complète avec le contrôleur de Jobs GKE ; MCP-089/MCP-092 |
| `ai-factory-m2` | jobs tests/qualité, créé dynamiquement | RW partagé | accélère Maven mais peut mélanger des artefacts entre tâches | cache proxy/mirror contrôlé ou cache segmenté et non fiable |
| prompts hôte vers `/opt/ai-factory/resources/prompts` | orchestrateur | RO | source des prompts versionnés | registre signé/versionné ; jamais modifiable par un agent |
| `gitea-data`, `gitea-db-data` | Gitea/PostgreSQL | persistants | données SCM | service SCM d'entreprise, hors serveurs MCP |
| `sonar-data`, `sonar-logs`, `sonar-extensions`, `sonar-db-data` | SonarQube/PostgreSQL | persistants | moteur de qualité | service SonarQube d'entreprise, hors serveurs MCP |
| `artifactory-data`, `artifactory-db-data` | Artifactory/PostgreSQL | persistants | dépôt de dépendances | service Artifactory/Artifact Registry, hors serveurs MCP |
| `grafana-data` | Grafana | persistant | tableaux de bord | plateforme d'observabilité |
| stockage de preuves | non implémenté ; actuellement fichiers du workspace | mutable | aucune immutabilité/rétention garantie | volume objet local puis Cloud Storage versionné, rétention et Bucket Lock après validation |

## 5. Inventaire réseau et destinations

### 5.1 Segments Compose

| Réseau | Membres utiles au flux MCP | Propriété actuelle | Limite constatée |
|---|---|---|---|
| `factory` / `ai-factory-network` | orchestrateur, LiteLLM, Gitea, SonarQube, Artifactory, bases, web et observabilité | réseau applicatif partagé ; aucun job sandbox n'y est désormais raccordé | conserver l'absence de jobs non fiables sur ce segment |
| `mcp-internal` / `ai-factory-mcp-internal` | orchestrateur, `repository-context-mcp`, `sandbox-execution-mcp`, Prometheus | réseau Docker `internal: true` sans egress direct | absence d'authentification applicative ; Prometheus et l'orchestrateur peuvent joindre les deux serveurs |
| `sandbox-egress` / `ai-factory-sandbox-egress` | jobs sandbox dynamiques, Artifactory, SonarQube et proxy d'egress | réseau `internal: true`; proxy seul raccordé aussi à `factory`, avec refus par défaut | porter l'isolation et les règles de destination vers GKE (MCP-216) |
| `none` | jobs `validate_patch` et `apply_patch` | aucun réseau | conforme au besoin | conserver dans la cible sandbox |

### 5.2 Flux requis et flux à retirer

| Source | Destination actuelle | Besoin | État cible |
|---|---|---|---|
| navigateur | reverse proxy `:80` | IHM/API | ingress authentifié et protégé |
| reverse proxy | orchestrateur `:8080` | API | seul chemin applicatif entrant |
| orchestrateur | LiteLLM `:4000` | inférence | conserver avec identité dédiée, quotas et egress fournisseur centralisé |
| orchestrateur | `repository-context-mcp:8091/mcp` | outils de contexte | conserver sur réseau privé avec audience/scopes dédiés |
| orchestrateur | `sandbox-execution-mcp:8092/mcp` | jobs sandbox | conserver sur réseau privé avec audience/scopes dédiés |
| orchestrateur | Gitea `:3000` | livraison directe | retirer après activation de `scm-delivery-mcp` |
| `repository-context-mcp` | aucune destination réseau métier | lecture du volume RO | rester sans egress |
| contrôleur sandbox | socket Unix Docker | création/inspection de conteneurs | remplacer par API contrôleur GKE, sans droit de création de Job pour l'orchestrateur |
| job tests | Artifactory/mirrors Maven/npm | dépendances | egress proxy allow-listé par FQDN/port et profil |
| job qualité | SonarQube + Artifactory | scan et dépendances | egress proxy allow-listé ; aucune autre destination interne |
| job sécurité | réseau `factory` | aucun besoin si bases Syft/Trivy préchargées | réseau `none`; mise à jour des bases dans un pipeline d'image séparé |
| futur `scm-delivery-mcp` | Gitea/SCM | métadonnées, push et draft PR | destination SCM unique par registre, identité et scope dépôt |
| futur `assurance-mcp` | SonarQube et stockage de preuves | lecture de résultats | lecture seule, destinations explicites |
| futur `evidence-mcp` | stockage objet/KMS | preuves et signature | URI logiques, bucket et clé limités à l'environnement |
| Prometheus | endpoints Actuator orchestrateur/MCP | métriques | identité de collecte ; aucun accès aux endpoints outils |

## 6. Comptes techniques et identités

| Identité actuelle | Nature/droits | Problème | Identité cible |
|---|---|---|---|
| processus orchestrateur | utilisateur par défaut de l'image, actuellement sans directive `USER` dédiée | identité Unix non durcie et jeton Gitea détenu | utilisateur non-root + `sa-orchestrator`, client MCP seulement |
| `context-mcp` UID `10001` | non-root, volume workspace RO | pas encore d'identité applicative pour authentifier l'appelant | `sa-repository-context-mcp` avec aucune permission réseau/backend |
| `sandbox-mcp` UID `10002` + groupe du socket | non-root dans le conteneur mais accès puissant au daemon Docker | privilège effectif élevé | `sa-sandbox-controller`; droit minimal de créer seulement les jobs/profils approuvés |
| conteneur sandbox | identité de l'image, sans compte métier propre | partage du daemon/réseau/cache | service account Kubernetes par classe de job ou identité éphémère par tentative, gVisor |
| `aiadmin` Gitea | compte administrateur POC utilisé pour la livraison | sur-privilégié et confondu avec le bootstrap | compte technique SCM non-admin, scope dépôt/PR ; reviewers nominatifs séparés |
| compte lié à `SONAR_TOKEN` | scanner SonarQube | portée à vérifier | identité d'analyse dédiée ; identité Assurance distincte en lecture |
| compte lié à `ARTIFACTORY_TOKEN` | lecture du miroir | même secret pour build d'image et runtime | identités distinctes CI/build et sandbox, lecture par dépôt |
| comptes PostgreSQL Gitea/Sonar/Artifactory | comptes de service de bases dédiées | mots de passe statiques Compose | identités/Secrets de plateforme, jamais accessibles aux MCP |

La cible GCP applique un compte de service distinct pour l'orchestrateur et chaque serveur MCP, Workload Identity sans fichier de clé, Secret Manager, audiences dédiées et autorisation par outil. Les noms ci-dessus sont des rôles logiques ; les identifiants IAM définitifs seront fixés avec la landing zone.

## 7. Écarts à traiter dans le backlog

| ID | Écart constaté | Jalon de traitement |
|---|---|---|
| D1 | `GITEA_TOKEN` et l'accès Gitea direct restent dans l'orchestrateur. | MCP-110 à MCP-123 |
| D2 | Les serveurs MCP ne valident pas encore une identité, une audience et des scopes propres à l'appelant. | MCP-210 à MCP-213 |
| D3 | La séparation et l'allow-list sont effectives dans Docker local ; leur équivalent GKE, DNS et metadata reste à appliquer. | MCP-216 |
| D4 | `docker.sock` reste monté dans le contrôleur sandbox local. | MCP-089 et MCP-092 |
| D5 | Secrets runtime statiques dans `.env`/`.vault`, malgré une bonne séparation partielle des détenteurs. | MCP-217 |
| D6 | Workspace partagé et cache Maven non segmenté par tentative. | schémas communs `attempt_id`, lot sandbox et MCP-145 à MCP-151 |
| D7 | Aucun backend immuable de preuves n'existe encore. | MCP-145 à MCP-151 |
| D8 | Les comptes bootstrap/admin du POC ne sont pas des identités minimales de production. | MCP-110, MCP-213 et procédures de plateforme |

## 8. Critères de clôture de MCP-003

- [x] Les secrets runtime et de plateforme sont nommés sans exposer leur valeur.
- [x] Les volumes partagés, persistants et privilégiés sont recensés.
- [x] Les réseaux et destinations nécessaires ou excessives sont identifiés.
- [x] Les comptes techniques actuels et identités cibles sont distingués.
- [x] Chaque dépendance métier a un propriétaire cible et un jalon de retrait.
