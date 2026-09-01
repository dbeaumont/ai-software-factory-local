# Sécurité du prototype

## Statut du document

Cette note décrit la posture de sécurité réelle du prototype local au 31 août 2026. Le projet est un POC (Proof of Concept) d'usine logicielle agentique. Il n'a pas vocation à être exposé tel quel sur un réseau d'entreprise non sécurisé ni en environnement de production.

## Hypothèses du POC

- Usage local sur poste de développement ou VM dédiée ;
- Utilisateurs de confiance ayant un accès local ;
- Dépôts de démonstration ou de faible criticité ;
- Objectif principal : valider la chaîne agentique, le contrôle déterministe et l'ergonomie d'approbation.

## Contrôles actuellement présents

### Validation humaine obligatoire (Human-in-the-loop)

- Aucun commit, push ou création de Pull Request n'a lieu avant l'appel explicite de validation `POST /api/tasks/{id}/approve` (via l'IHM ou l'API REST).
- Le prototype sépare formellement la phase de génération/validation locale et la phase de livraison vers le SCM.

### Exécution isolée en sandbox Docker

L'orchestrateur ne possède plus le socket Docker. Il soumet des opérations MCP déterministes au service interne
`sandbox-execution-mcp`, qui est le seul contrôleur Compose autorisé à monter `/var/run/docker.sock`. Le contrôleur
accepte uniquement sept outils et cinq profils immuables ; aucun appel ne peut fournir une commande, une image,
un réseau, un volume ou une variable d'environnement.

Les étapes de vérification du patch, d'exécution des tests, d'analyse qualimétrique et de scans de sécurité s'exécutent dans un conteneur Docker éphémère `ai-factory-sandbox:local` configuré avec des restrictions strictes :

- `--rm` (destruction automatique du conteneur après exécution)
- `--memory 2g` (limite mémoire de 2 Go)
- `--cpus 2` (limite à 2 cœurs CPU)
- `--pids-limit 512` (limitation du nombre de processus pour éviter les attaques fork-bomb)
- `--cap-drop ALL` (suppression de toutes les capacités Linux du conteneur)
- `--security-opt no-new-privileges` (interdiction d'élévation de privilèges)

Profils réseau de la sandbox :

- **Validation du patch (`checkPatch` et `applyPatch`)** : exécutée avec `--network none` (isolation réseau totale).
- **Builds, tests, SonarQube et Trivy** : exécutés sur `ai-factory-sandbox-egress`, réseau interne dédié sans accès au plan de contrôle ; les sorties passent par un proxy à liste blanche.

### Contrôles déterministes

- Normalisation et validation de la syntaxe du patch unifié via `git apply --check` ;
- Application du patch puis vérification de la propreté via `git diff --check` et `git diff --stat` ;
- Lancement déterministe des suites de tests automatisées (Maven, Gradle, npm) ;
- Génération d'un inventaire logiciel SBOM au format CycloneDX JSON via Syft ;
- Analyse des vulnérabilités logicielles et secrets avec Trivy ;
- Transit des dépendances Maven par le miroir Artifactory local (`ai-factory-m2` volume) ;
- Analyse de la qualité de code par SonarQube pour les projets Java/Maven ;
- Conservation et isolation des traces d'exécution dans le workspace local sous `.ai-factory/`.

### Réduction du risque et étanchéité des secrets

- Mode cloud activé par défaut (`AI_FACTORY_CLOUD_ENABLED=true`) ;
- Le navigateur n'a jamais accès à la clé OpenAI ni aux jetons d'administration ;
- L'orchestrateur ne gère pas la clé OpenAI directement : elle est injectée de manière étanche dans le conteneur `litellm` via le fichier `.vault` ;
- Les jetons d'accès `GITEA_TOKEN` et `SONAR_TOKEN` sont générés par le script de bootstrap et enregistrés localement dans `.env` ;
- Avant la création de la Pull Request, tous les artefacts temporaires générés par l'IA (`.ai-plan.md`, `changes.patch`, `.ai-review.md`, `.ai-factory/`) sont systématiquement retirés de l'index Git (`git reset`).
- Les jetons SonarQube et Artifactory nécessaires aux jobs ne sont plus injectés dans l'orchestrateur. Le contrôleur sandbox les place dans un fichier temporaire de permissions `0600`, puis le détruit après le job.

### Point d'entrée HTTP unique

- Le conteneur `reverse-proxy` (Nginx) est le point d'entrée HTTP sur le port `WEB_APP_PORT` (8080 par défaut).
- Nginx sert l'application frontend `factory-web` sur `/` et relaie uniquement le préfixe `/api/` vers l'orchestrateur (`orchestrator`).
- L'IHM communique exclusivement par des URLs relatives (`/api/tasks`), évitant d'exposer l'adresse interne des services au navigateur.

## Risques et limites actuels

### 1. `docker.sock` monté dans le contrôleur sandbox local

Le service `sandbox-execution-mcp` monte encore le socket Docker du hôte. Sa surface est plus petite que celle de l'orchestrateur et son conteneur est non-root, read-only, sans capabilities et sans port hôte, mais une compromission capable d'utiliser le socket donnerait toujours un contrôle quasi total du démon Docker.

- *Acceptable* dans un POC local isolé.
- *Inacceptable* pour une cible de production (à remplacer par des Jobs Kubernetes ou une Sandbox API). Le démarrage de ce contrôleur doit être explicitement accepté dans les environnements qui contrôlent l'accès au socket.

### 2. Réseau interne partagé pour les builds

Les builds et scans de la sandbox sont raccordés au seul réseau `ai-factory-sandbox-egress`, déclaré `internal: true`. Ce segment contient Artifactory, SonarQube et le proxy d'egress ; Gitea, LiteLLM, l'orchestrateur, les serveurs MCP, les bases et le frontal n'y sont pas raccordés. Le proxy applique un refus par défaut et une liste de domaines de dépendances versionnée dans le dépôt.

### 3. Secrets locaux en texte clair

Le POC s'appuie sur des secrets stockés dans `.env` et `.vault` (notamment `GITEA_TOKEN`, `SONAR_TOKEN`, `VAULT_OPENAI_API_KEY`). Il convient de s'assurer que ces fichiers restent exclus de tout dépôt Git distant (déjà déclarés dans `.gitignore`).

### 4. Volatilité des données de l'orchestrateur

L'historique des tâches est conservé en mémoire dans l'orchestrateur Spring Boot. Tout redémarrage remet à zéro la liste des tâches (les workspaces sur disque restent toutefois présents dans le volume `factory-workspace`).

### 5. Sorties des LLM par nature probabilistes

Même si la chaîne impose des filtres et une boucle de réparation de patch, les modèles de langage peuvent générer des modifications imperfectes. La validation déterministe (tests + SonarQube + Trivy) et la revue humaine préalable restent le dernier niveau de contrôle indispensable.

## Recommandations pour l'utilisation et la démo

- Exécuter la stack sur un environnement local ou une VM dédiée ;
- Utiliser uniquement des dépôts de démonstration ou non sensibles ;
- Modifier les mots de passe par défaut de Gitea (`GITEA_ADMIN_PASSWORD`) et SonarQube ;
- Ne jamais exposer les ports de la stack sur un réseau public sans filtrage ou VPN ;
- Exclure le fichier `.vault` du contrôle de version (vérifié dans `.gitignore`).

## Cible industrielle recommandée

Pour passer d'un POC local à une cible crédible, il faut au minimum :

1. Remplacer `docker.sock` par une Sandbox API ou des Jobs Kubernetes.
2. Introduire des identités workload et un gestionnaire de secrets.
3. Porter les restrictions réseau Docker vers les NetworkPolicies GKE, le DNS contrôlé et le blocage metadata suivis par MCP-216.
4. Persister l'état et l'audit des tâches dans une base dédiée.
5. Ajouter SSO, RBAC et policy-as-code.
6. Isoler strictement les plans contrôle, contexte, exécution et livraison.
7. Mettre en place la signature des artefacts, SBOM et attestations.
8. Gouverner les modèles, prompts, outils et niveaux d'autonomie.

## Résumé

Le prototype met déjà les bons points de passage de contrôle, mais pas encore le niveau d'isolation ni de gouvernance attendu pour un environnement sensible. Son intérêt est de rendre visibles les risques et les garde-fous d'une chaîne agentique, pas de servir de socle de production.
