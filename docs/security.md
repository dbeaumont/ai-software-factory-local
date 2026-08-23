# Sécurité du prototype

## Statut du document

Cette note décrit la posture de sécurité réelle du dépôt au 23 août 2026. Le projet reste un POC local. Il ne doit pas être exposé tel quel sur un réseau d'entreprise ou en production.

## Hypothèses du POC

- usage local sur poste de démonstration ou machine dédiée ;
- utilisateurs de confiance ;
- dépôts de démonstration ou de faible criticité ;
- besoin principal : valider la chaîne agentique, pas tenir une posture de production.

## Contrôles actuellement présents

### Validation humaine obligatoire

- aucun commit, push ou création de PR n'a lieu avant `POST /api/tasks/{id}/approve` ;
- une tâche `dryRun=true` ne peut pas être approuvée ;
- le prototype sépare explicitement génération et livraison.

### Exécution isolée en sandbox

Les étapes patch, tests et sécurité s'exécutent dans des conteneurs Docker éphémères avec :

- `--rm`
- `--memory 2g`
- `--cpus 2`
- `--pids-limit 512`
- `--cap-drop ALL`
- `--security-opt no-new-privileges`

Deux profils réseau existent :

- validation du patch avec `--network none`
- tests et scans avec le réseau Docker local `ai-factory-local`

### Contrôles déterministes

- validation du diff via `git apply --check` ;
- application du patch puis `git diff --check` en exécution complète ;
- tests build/run déterministes ;
- génération d'un SBOM CycloneDX avec Syft ;
- scan vulnérabilités et secrets avec Trivy ;
- conservation des traces d'exécution dans le workspace.

### Réduction du risque par défaut

- `dryRun=true` par défaut ;
- mode cloud désactivé par défaut ;
- le navigateur ne reçoit jamais la clé OpenAI ;
- l'orchestrateur ne reçoit pas directement la clé OpenAI ;
- la clé est injectée dans LiteLLM seulement.

## Risques et limites actuels

### 1. `docker.sock` monté dans l'orchestrateur

Le service `orchestrator` monte `/var/run/docker.sock`. En pratique, cela donne un contrôle très large sur le démon Docker de l'hôte. Pour un attaquant qui compromettrait l'orchestrateur, cela représente quasi un accès root sur la machine Docker.

Conséquence :

- acceptable pour un POC local isolé ;
- inacceptable pour une cible de production.

### 2. Réseau permissif pour tests et scans

Les builds et scans rejoignent le réseau Docker `ai-factory-local`. Cela permet :

- la résolution des dépendances ;
- l'accès aux services internes de la stack ;
- potentiellement une surface latérale plus large que souhaité.

Le prototype ne met pas encore en place :

- d'egress allow-list ;
- de proxy d'artefacts obligatoire ;
- de segmentation réseau fine ;
- de politique par type de tâche.

### 3. Secrets en `.env`

Le POC repose sur des secrets simples dans `.env`, notamment :

- `OPENAI_API_KEY`
- `LITELLM_MASTER_KEY`
- `GITEA_TOKEN`
- `GITEA_ADMIN_PASSWORD`

Risques :

- fuite locale ;
- partage involontaire du fichier ;
- rotation non gouvernée ;
- absence d'audit central.

### 4. Tâches non persistées

L'état des tâches est stocké en mémoire dans l'orchestrateur. Un redémarrage supprime l'historique applicatif exposé par l'API.

Impact :

- audit incomplet ;
- reprise impossible ;
- traçabilité partielle.

### 5. Sorties LLM non fiables par nature

Le système impose des contrôles, mais les sorties LLM peuvent toujours :

- proposer un patch incorrect ;
- produire un diff invalide ;
- faire échouer les tests ;
- générer une analyse inexacte côté `Tester` ou `Reviewer`.

Le prototype ne doit donc pas être interprété comme une chaîne autonome de livraison.

## Données qui sortent potentiellement de la machine

### Mode `LOCAL`

- requirement, contexte dépôt et preuves restent dans la stack Docker locale ;
- les dépendances logicielles du build peuvent néanmoins être téléchargées depuis Internet si le dépôt l'exige.

### Mode `CLOUD`

Si `AI_FACTORY_CLOUD_ENABLED=true` et que la tâche choisit `llmMode=CLOUD`, les données suivantes sont envoyées au fournisseur LLM via LiteLLM :

- requirement ;
- contexte du dépôt ;
- plan ;
- patch ;
- extraits de logs de test et de sécurité.

Le mode cloud doit donc être réservé à des contextes compatibles avec la politique de données applicable.

## Mesures minimales recommandées pour une démo

- utiliser une machine dédiée ;
- ne brancher que des dépôts non sensibles ;
- changer les credentials par défaut de Gitea ;
- limiter ou désactiver le mode cloud si inutile ;
- nettoyer régulièrement les volumes Docker ;
- ne jamais exposer les ports du POC sur Internet ;
- éviter d'utiliser des tokens à privilèges étendus.

## Cible industrielle recommandée

Pour passer d'un POC local à une cible crédible, il faut au minimum :

1. Remplacer `docker.sock` par une Sandbox API ou des Jobs Kubernetes.
2. Introduire des identités workload et un gestionnaire de secrets.
3. Ajouter des politiques réseau avec egress allow-list.
4. Persister l'état et l'audit des tâches dans une base dédiée.
5. Ajouter SSO, RBAC et policy-as-code.
6. Isoler strictement les plans contrôle, contexte, exécution et livraison.
7. Mettre en place la signature des artefacts, SBOM et attestations.
8. Gouverner les modèles, prompts, outils et niveaux d'autonomie.

## Résumé

Le prototype met déjà les bons points de passage de contrôle, mais pas encore le niveau d'isolation ni de gouvernance attendu pour un environnement sensible. Son intérêt est de rendre visibles les risques et les garde-fous d'une chaîne agentique, pas de servir de socle de production.
