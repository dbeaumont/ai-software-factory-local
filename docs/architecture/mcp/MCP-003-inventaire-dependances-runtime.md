# MCP-003 — Secrets, volumes, réseaux, identités et destinations

> Statut : validé pour le prototype Compose  
> Périmètre observé : `.env.example`, `infrastructure/compose.yaml`, configurations Spring et profils sandbox  
> Valeurs sensibles : volontairement exclues de ce document
>
> Mise à jour de cohérence du 3 septembre 2026 : état postérieur aux retraits MCP-057, MCP-089 et MCP-123 et à
> l'ajout des serveurs Assurance/Evidence.

## 1. Objet et méthode

Ce document établit la baseline des dépendances techniques utilisées par chaque capacité. Il inventorie les noms
et propriétaires des secrets, jamais leur valeur. Il distingue :

- le déploiement Compose actif ;
- les composants disponibles mais non intégrés au pipeline de référence ;
- la propriété attendue dans la cible MCP puis GCP.

Les fichiers locaux `.env` et `.vault` ne sont pas lus ni reproduits. Les noms proviennent des exemples et des références de configuration versionnées.

## 2. Vue par capacité

| Capacité | Composant actuel | Secret runtime | Stockage/volume | Réseau et destinations | Propriétaire cible |
|---|---|---|---|---|---|
| Orchestration | `orchestrator` | clé LiteLLM et clé d'attestation d'approbation | `factory-workspace` en lecture/écriture, prompts en lecture seule | `factory` vers LiteLLM ; `mcp-internal` vers les cinq serveurs MCP | `sa-orchestrator` ; aucun secret Gitea/Sonar/Artifactory/Docker |
| Inférence | `litellm`, fournisseur cloud | `LITELLM_MASTER_KEY`, `VAULT_OPENAI_API_KEY`/`OPENAI_API_KEY` | aucun volume de modèle | egress TLS vers le fournisseur cloud | identité LiteLLM dédiée, secrets fournisseur dans Secret Manager |
| Contexte dépôt | `repository-context-mcp` | aucun secret runtime | `factory-workspace:/workspace/tasks:ro` et registre de contexte | uniquement `mcp-internal`; aucun egress attendu | `sa-repository-context-mcp`, lecture limitée au workspace de la tentative |
| Validation/application de patch | `sandbox-execution-mcp` et runner sandbox | aucun secret fonctionnel | workspace partagé ; `sandbox-job-state` | MCP sur `mcp-internal`; runner sur `sandbox-control` sans egress | `sa-sandbox-controller` puis identité éphémère de tentative GKE |
| Tests | jobs sandbox `test-maven-v1`, `test-gradle-v1`, `test-node-v1` | `ARTIFACTORY_TOKEN` si miroir authentifié | workspace ; cache `ai-factory-m2` réservé à Maven | réseau interne `sandbox-egress`; Artifactory direct et domaines Gradle/npm via proxy allow-listé | contrôleur sandbox/identité de job, secret à durée courte |
| Qualité | job sandbox `quality-sonar-v1` | `SONAR_TOKEN`, éventuellement `ARTIFACTORY_TOKEN` | workspace, cache Maven, rapports `.ai-factory` | réseau interne `sandbox-quality`; SonarQube, Artifactory et proxy filtré | contrôleur sandbox pour produire le rapport ; `assurance-mcp` pour le verdict |
| Sécurité | job sandbox `security-syft-trivy-v1` | aucun dans le profil actuel | workspace, SBOM et rapport `.ai-factory` | réseau interne `sandbox-egress`; la cible reste un scan sans réseau avec bases préchargées | identité de job sandbox ; mises à jour de bases via pipeline séparé |
| Livraison SCM | `scm-delivery-mcp` | `GITEA_TOKEN` et nom `AI_FACTORY_GITEA_USER`, confinés au serveur | workspace source read-only puis staging privé | serveur SCM vers `gitea:3000` et push HTTP authentifié | `sa-scm-delivery-mcp`, jeton Gitea dédié jusqu'à fédération d'identité |
| Assurance | `assurance-mcp` stateless | aucun secret runtime | aucun volume | `mcp-internal` uniquement ; évalue les données transmises | `sa-assurance-mcp`, sans accès au code ni au SCM |
| Preuves | `evidence-mcp` | clé de chiffrement locale dérivée de la clé d'attestation | volume `evidence-state` immuable et chiffré | `mcp-internal` uniquement | `sa-evidence-mcp`, puis bucket Cloud Storage dédié et KMS |
| Observabilité | Collector OpenTelemetry/SigNoz | compte administrateur SigNoz hors flux MCP | volumes ClickHouse/PostgreSQL et définitions versionnées | OTLP privé sur les trois réseaux internes ; seule l'UI SigNoz est publiée | Workload Identity du Collector et rôles Google minimaux |

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
| `SIGNOZ_ROOT_PASSWORD` | SigNoz | administration locale initiale | plateforme observabilité/SSO | Généré localement, hors périmètre des serveurs MCP. |

### 3.2 Constats de sécurité

- Les valeurs d'exemple de certains comptes locaux sont faibles et réservées au POC ; elles ne constituent pas une cible partagée.
- Les cinq serveurs MCP n'authentifient pas encore l'appelant par identité applicative. Le réseau `mcp-internal`
  réduit l'exposition mais ne remplace pas issuer, audience, scopes et autorisation par outil.
- Le jeton Gitea est confiné à `scm-delivery-mcp`; l'orchestrateur n'en est plus détenteur.
- Le contrôleur sandbox reçoit correctement les secrets Sonar/Artifactory côté serveur. Ils ne sont injectés que dans les profils qui les déclarent, mais restent des variables d'environnement statiques dans Compose.
- Le nom d'une variable, une URL interne, un nom de volume ou un profil ne doit jamais être modifiable par une sortie de modèle.

## 4. Inventaire des volumes et systèmes de fichiers

| Volume/montage | Producteurs/consommateurs | Droits actuels | Risque ou usage | Cible |
|---|---|---|---|---|
| `factory-workspace` | orchestrateur RW ; `repository-context-mcp` RO ; contrôleur sandbox RO ; jobs sandbox RO ou RW selon profil | partagé entre tâches par nom de sous-répertoire | frontière interservices fondée sur un chemin local et isolation dépendante de `task_id` | espace par `task_id` + `attempt_id`, handle opaque, source figée et artefacts par digest |
| `sandbox-job-state` | `sandbox-execution-mcp` | RW | persistance locale des handles et heartbeats | stockage durable/idempotent du contrôleur ; Cloud SQL/Firestore selon choix d'architecture |
| runners Compose statiques | `sandbox-execution-mcp` via API interne authentifiée | profils et réseaux séparés, aucun port hôte | isolation de processus plus faible que gVisor | usage local uniquement ; Jobs GKE en environnement partagé |
| `ai-factory-m2` | jobs tests/qualité, créé dynamiquement | RW partagé | accélère Maven mais peut mélanger des artefacts entre tâches | cache proxy/mirror contrôlé ou cache segmenté et non fiable |
| prompts hôte vers `/opt/ai-factory/resources/prompts` | orchestrateur | RO | source des prompts versionnés | registre signé/versionné ; jamais modifiable par un agent |
| `gitea-data`, `gitea-db-data` | Gitea/PostgreSQL | persistants | données SCM | service SCM d'entreprise, hors serveurs MCP |
| `sonar-data`, `sonar-logs`, `sonar-extensions`, `sonar-db-data` | SonarQube/PostgreSQL | persistants | moteur de qualité | service SonarQube d'entreprise, hors serveurs MCP |
| `artifactory-data`, `artifactory-db-data` | Artifactory/PostgreSQL | persistants | dépôt de dépendances | service Artifactory/Artifact Registry, hors serveurs MCP |
| volumes SigNoz ClickHouse/PostgreSQL | SigNoz | persistants | métriques, traces, logs, dashboards et alertes | Google Cloud Observability en cible GKE |
| `evidence-state` | `evidence-mcp` | RW par le serveur, non monté dans l'orchestrateur | stockage local immuable, chiffré et lié aux digests | Cloud Storage avec CMEK, précondition de création et rétention verrouillée |

## 5. Inventaire réseau et destinations

### 5.1 Segments Compose

| Réseau | Membres utiles au flux MCP | Propriété actuelle | Limite constatée |
|---|---|---|---|
| `factory` / `ai-factory-network` | orchestrateur, LiteLLM, Gitea, SonarQube, Artifactory, bases, web et observabilité | réseau applicatif partagé ; aucun job sandbox n'y est désormais raccordé | conserver l'absence de jobs non fiables sur ce segment |
| `mcp-internal` / `ai-factory-mcp-internal` | orchestrateur, cinq serveurs MCP et Collector OTel | réseau Docker `internal: true` sans egress direct | OTLP local sans mTLS, acceptable uniquement sur ce réseau isolé |
| `sandbox-egress` / `ai-factory-sandbox-egress` | jobs de tests/sécurité, Artifactory et proxy d'egress | réseau `internal: true`; proxy seul raccordé aussi à `factory`, avec refus par défaut | porter l'isolation et les règles de destination vers GKE |
| `sandbox-quality` / `ai-factory-sandbox-quality` | jobs qualité, SonarQube, Artifactory et proxy d'egress | réseau `internal: true`; profil choisi côté serveur | conserver la séparation des profils dans la cible GKE |
| `none` | jobs `validate_patch` et `apply_patch` | aucun réseau | conforme au besoin | conserver dans la cible sandbox |

### 5.2 Flux requis et flux à retirer

| Source | Destination actuelle | Besoin | État cible |
|---|---|---|---|
| navigateur | reverse proxy `:80` | IHM/API | ingress authentifié et protégé |
| reverse proxy | orchestrateur `:8080` | API | seul chemin applicatif entrant |
| orchestrateur | LiteLLM `:4000` | inférence | conserver avec identité dédiée, quotas et egress fournisseur centralisé |
| orchestrateur | `repository-context-mcp:8091/mcp` | outils de contexte | conserver sur réseau privé avec audience/scopes dédiés |
| orchestrateur | `sandbox-execution-mcp:8092/mcp` | jobs sandbox | conserver sur réseau privé avec audience/scopes dédiés |
| orchestrateur | `scm-delivery-mcp:8093/mcp` | résolution SCM et livraison approuvée | conserver sur réseau privé avec identité dédiée |
| `repository-context-mcp` | aucune destination réseau métier | lecture du volume RO | rester sans egress |
| contrôleur sandbox | runners Compose sur `sandbox-control` ou API Kubernetes | soumission de profils immuables | aucun droit de création de Job pour l'orchestrateur |
| job tests | Artifactory/mirrors Maven/npm | dépendances | egress proxy allow-listé par FQDN/port et profil |
| job qualité | SonarQube + Artifactory | scan et dépendances | egress proxy allow-listé ; aucune autre destination interne |
| job sécurité | proxy filtré sur `sandbox-egress` | bases Syft/Trivy | viser des bases préchargées et un réseau `none` lorsque le cycle de mise à jour est maîtrisé |
| `scm-delivery-mcp` | Gitea/SCM | métadonnées, push et draft PR | destination SCM unique par registre, identité et scope dépôt |
| `assurance-mcp` | aucune destination métier directe | évaluation des résultats fournis | conserver stateless et sans code source complet |
| `evidence-mcp` | volume local chiffré | preuves et manifests | stockage objet/KMS limité à l'environnement en cible |
| Collector OTel | endpoints OTLP des applications et endpoint métrique Temporal | métriques, traces et logs expurgés | Workload Identity et accès en écriture aux backends Google uniquement |

## 6. Comptes techniques et identités

| Identité actuelle | Nature/droits | Problème | Identité cible |
|---|---|---|---|
| processus orchestrateur UID `10000` | non-root, client MCP seulement | identité workload encore absente en Compose | `sa-orchestrator`, client MCP seulement |
| `context-mcp` UID `10001` | non-root, volume workspace RO | pas encore d'identité applicative pour authentifier l'appelant | `sa-repository-context-mcp` avec aucune permission réseau/backend |
| `sandbox-mcp` UID `10002` | non-root et sans accès au daemon | authentification locale par secret partagé | `sa-sandbox-controller`; droit minimal de créer seulement les jobs/profils approuvés |
| runner sandbox UID `10000` | non-root, réseau et profils bornés par service | runner persistant en local | service account Kubernetes sans token monté, gVisor |
| `aiadmin` Gitea | compte administrateur POC utilisé pour la livraison | sur-privilégié et confondu avec le bootstrap | compte technique SCM non-admin, scope dépôt/PR ; reviewers nominatifs séparés |
| compte lié à `SONAR_TOKEN` | scanner SonarQube | portée à vérifier | identité d'analyse dédiée ; identité Assurance distincte en lecture |
| compte lié à `ARTIFACTORY_TOKEN` | lecture du miroir | même secret pour build d'image et runtime | identités distinctes CI/build et sandbox, lecture par dépôt |
| comptes PostgreSQL Gitea/Sonar/Artifactory | comptes de service de bases dédiées | mots de passe statiques Compose | identités/Secrets de plateforme, jamais accessibles aux MCP |

La cible GCP applique un compte de service distinct pour l'orchestrateur et chaque serveur MCP, Workload Identity sans fichier de clé, Secret Manager, audiences dédiées et autorisation par outil. Les noms ci-dessus sont des rôles logiques ; les identifiants IAM définitifs seront fixés avec la landing zone.

## 7. Écarts à traiter dans le backlog

| ID | Écart constaté | Jalon de traitement |
|---|---|---|
| D1 | Clos : jeton et accès Gitea direct retirés de l'orchestrateur ; livraison confiée à `scm-delivery-mcp`. | MCP-123 |
| D2 | Les serveurs MCP ne valident pas encore une identité, une audience et des scopes propres à l'appelant ; TLS est traité séparément. | MCP-211 à MCP-213 |
| D3 | La séparation et l'allow-list sont effectives dans Docker local ; leur équivalent GKE, DNS et metadata reste à appliquer. | MCP-092/MCP-216 |
| D4 | Clos : socket supprimée, runtime Docker retiré et contrôle de non-régression ajouté. | MCP-089 et MCP-092 |
| D5 | Secrets runtime statiques dans `.env`/`.vault`, malgré une bonne séparation partielle des détenteurs. | MCP-217 |
| D6 | Workspace partagé et cache Maven non segmenté par tentative. | schémas communs `attempt_id`, lot sandbox et MCP-145 à MCP-151 |
| D7 | Clos localement : Evidence MCP fournit un backend chiffré et immuable ; l'adaptateur GCS reste à implémenter. | MCP-145 à MCP-151 |
| D8 | Les comptes bootstrap/admin du POC ne sont pas des identités minimales de production. | MCP-110, MCP-213 et procédures de plateforme |

## 8. Critères de clôture de MCP-003

- [x] Les secrets runtime et de plateforme sont nommés sans exposer leur valeur.
- [x] Les volumes partagés, persistants et privilégiés sont recensés.
- [x] Les réseaux et destinations nécessaires ou excessives sont identifiés.
- [x] Les comptes techniques actuels et identités cibles sont distingués.
- [x] Chaque dépendance métier a un propriétaire cible et un jalon de retrait.
