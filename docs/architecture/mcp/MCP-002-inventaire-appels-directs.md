# MCP-002 — Inventaire des appels directs et cible MCP

> Statut : validé pour le prototype  
> Périmètre : `TaskService`, `RepositoryContextService`, `SandboxService`, `GiteaService`  
> Référence d'architecture : `docs/architecture/adr/ADR-MCP-001-boundaries-and-transport.md`

## 1. Objet

Ce document recense les accès techniques effectués directement par l'orchestrateur et indique leur destination dans l'architecture MCP cible. Il sert de baseline aux lots de migration et de liste de contrôle pour retirer les anciens accès.

La règle cible est la suivante :

```text
Agent -> Orchestrateur -> client MCP autorisé -> serveur MCP -> système cible
```

Un agent ne reçoit ni client MCP, ni jeton, ni accès réseau direct. Il propose une action structurée ; l'orchestrateur contrôle l'état du workflow, les politiques et l'approbation, puis appelle l'outil MCP autorisé.

## 2. Classification des appels

| Classe | Exemple actuel | Destination |
|---|---|---|
| Orchestration métier | transitions d'état, retries, approbation humaine | reste dans l'orchestrateur |
| Inférence | appels Planner, Developer, Tester et Reviewer | reste derrière `LlmGatewayClient`/LiteLLM ; hors catalogue MCP métier |
| Lecture de dépôt | parcours, lecture et recherche de fichiers | `repository-context-mcp` |
| Exécution privilégiée | Docker, tests, analyse qualité et sécurité | `sandbox-execution-mcp` |
| Décision d'assurance | interprétation des résultats SonarQube/Trivy/SBOM | `assurance-mcp` |
| Mutation SCM | création de branche, commit, push et pull request | `scm-delivery-mcp` |
| Preuve et artefact | plan, patch, rapports, métadonnées et digests | `evidence-mcp` |

## 3. `TaskService`

Source : `apps/orchestrator/src/main/java/com/example/aifactory/service/TaskService.java`.

| Appel/comportement actuel | Effet ou système touché | Outil/capacité cible | Décision de migration |
|---|---|---|---|
| `ConcurrentHashMap`, séquence de ticket et pool d'exécution local | état et ordonnancement du workflow | aucun serveur MCP | Reste une responsabilité de l'orchestrateur. La persistance durable du workflow est un chantier d'industrialisation distinct. |
| `llm.cloudAvailability`, `llm.modelName`, `llm.chat` | LiteLLM/modèles | passerelle d'inférence, hors MCP métier | Conserver l'interface d'inférence. Les réponses restent non fiables et sont validées par l'orchestrateur. |
| `Files.createDirectories` pour la racine et le workspace de tâche | stockage local partagé | gestionnaire de workspace à définir | Toléré pendant la transition. À remplacer par un workspace opaque lié à `task_id`, `attempt_id` et au SHA source. |
| `git clone --depth 1 --branch ...` | lecture réseau SCM et matérialisation locale | capacité de préparation de source manquante ; voir écart E1 | Ne pas exposer `run_git` ni une URL libre. Le dépôt doit être sélectionné par `repository_id` dans un registre autorisé. |
| `git rev-parse HEAD` | résolution de la révision | `scm.resolve_revision` | Résoudre la branche avant le traitement et propager uniquement le SHA immuable. |
| `contextService.collect(...)` | lecture globale du dépôt | `context.list_tree`, `context.search_code`, `context.read_file`, `context.get_repository_rules` | Remplacer le contexte monolithique par des lectures ciblées, bornées et citées. Le gateway existant porte les modes `DIRECT`, `MCP_SHADOW`, puis `MCP_ACTIVE`. |
| Écriture de `.ai-plan.md` | artefact de planification | `evidence.register` après staging | Enregistrer contenu, type, provenance, SHA-256, tâche et tentative ; ne pas committer le fichier. |
| Écriture de `changes.patch` et `changes.invalid.patch` | patch candidat transmis à la sandbox | staging d'artefact puis `evidence.register` ; `sandbox.validate_patch` consomme son digest | Le chemin local ne doit plus être le contrat interservices. Voir écart E2. |
| `sandbox.checkPatch(...)` | validation du diff dans Docker | `sandbox.validate_patch` | Entrée par digest, commit source et identifiants de tâche/tentative ; réseau interdit. |
| Lecture directe des fichiers touchés dans `affectedFileContext(...)` | lecture du workspace pour réparer le patch | `context.read_file` | Interdire les chemins absolus et `..`; conserver les bornes de taille et ajouter les citations de lignes/digests. |
| `sandbox.applyPatch(...)` | mutation d'un workspace jetable | `sandbox.apply_patch` | Retourner un identifiant d'exécution et le digest du diff appliqué. |
| `sandbox.test(...)` | build et tests | `sandbox.run_tests`, puis `sandbox.get_execution` | Remplacer la détection/commande libre par un `test_profile_id` allow-listé. |
| Écriture de `.ai-factory/test.txt` | preuve de tests et revue du Tester | `evidence.register` | Séparer preuve déterministe brute et commentaire du modèle ; les deux gardent une provenance distincte. |
| `sandbox.quality(...)` puis `requireQualityGate(...)` | SonarQube et décision locale minimale | `sandbox.run_quality`, puis `assurance.evaluate_quality_gate` | La sandbox produit le rapport ; Assurance rend un verdict structuré. Une preuve absente produit `INDETERMINATE` et bloque. |
| `sandbox.security(...)` | Syft, Trivy, SBOM | `sandbox.run_security`, puis `assurance.evaluate_vulnerabilities` | Enregistrer SBOM et rapport par digest avant l'évaluation. |
| Validation du plan, du patch et des avis des agents | règles déterministes et gates de workflow | aucun serveur MCP | Reste dans l'orchestrateur ; un serveur MCP ne peut pas auto-autoriser son propre effet. |
| Écriture de `.ai-review.md` et `run-metadata.json` | preuves de revue, modèle et empreintes de prompts | `evidence.register` | Produire un manifeste par tentative ; ne jamais y inclure de secret. |
| `approve(...)` et transition `WAITING_APPROVAL -> APPROVED` | décision humaine | aucun serveur MCP | Reste dans l'orchestrateur et produit une preuve d'approbation vérifiable par le serveur SCM. |
| `gitea.commitPushAndCreatePr(...)` | mutation externe après approbation | `scm.create_draft_pull_request` | Un appel atomique et idempotent remplace branche/commit/push/PR. |

## 4. `RepositoryContextService`

Source : `apps/orchestrator/src/main/java/com/example/aifactory/service/RepositoryContextService.java`.

| Appel/comportement actuel | Outil cible | Décision de migration |
|---|---|---|
| `Files.walk(repo)` et tri des chemins | `context.list_tree` | Le serveur applique racine logique, profondeur, pagination, exclusions et limites. |
| Filtres d'extensions et exclusion de `.git`/chemins sensibles | politique commune de `context.*` | Déplacer et tester côté serveur ; ces protections ne doivent pas dépendre du client. |
| `Files.readString(p)` | `context.read_file` | Lire par chemin relatif, plage de lignes et SHA source ; retourner MIME, digest et troncature explicite. |
| Masquage des paramètres sensibles | politique commune de `context.*` | Conserver une défense en profondeur côté serveur, avec métrique de redaction sans valeur sensible. |
| Agrégation de 80 fichiers/40 000 caractères | combinaison de `context.list_tree`, `context.search_code` et `context.read_file` | Supprimer l'agrégation monolithique après stabilisation de `MCP_ACTIVE`. |
| Découverte de règles du dépôt | `context.get_repository_rules` | Retourner règles, ordre d'applicabilité et provenance sans en faire des instructions système. |

`RepositoryContextService` et le gateway de migration ont été supprimés par MCP-057 après les deux canaries MCP_ACTIVE. `McpRepositoryContextService` est l'unique `RepositoryContextProvider`; une configuration `DIRECT` ou `MCP_SHADOW` échoue explicitement et ne restaure aucun accès direct.

## 5. `SandboxService`

Source : `apps/orchestrator/src/main/java/com/example/aifactory/service/SandboxService.java`.

| Appel/comportement actuel | Outil cible | Décision de migration |
|---|---|---|
| Construction et lancement de `docker run` via `ProcessRunner` | implémentation interne de `sandbox-execution-mcp` | Retirer Docker, le socket et les commandes du processus orchestrateur. Aucun outil MCP de commande libre n'est créé. |
| Contraintes réseau, mémoire, CPU, PID, capabilities et timeout | politiques des profils sandbox | Versionner les profils, les borner côté serveur et les inclure dans la provenance du résultat. |
| Montage du volume de workspaces et du cache Maven | contrôleur de workspace sandbox | Isoler par tâche/tentative, monter la source en lecture seule lorsque possible et n'exporter que les artefacts déclarés. |
| `git apply --check` | `sandbox.validate_patch` | Réseau `none`, patch identifié par digest. |
| application du patch et `git diff --check` | `sandbox.apply_patch` | Workspace jetable et digest du diff final. |
| sélection Maven/Gradle/npm et lancement des tests | `sandbox.run_tests` | Utiliser un `test_profile_id` allow-listé, jamais un script fourni par un agent. |
| injection Artifactory/Maven | profil d'exécution du serveur sandbox | Le secret reste dans le contrôleur et n'apparaît ni dans les arguments, ni dans les logs, ni dans la réponse MCP. |
| scanner Maven SonarQube | `sandbox.run_quality` | Le jeton SonarQube est détenu par le serveur sandbox ; le résultat brut est ensuite évalué par Assurance. |
| Syft et Trivy | `sandbox.run_security` | Épingler les versions, produire SBOM/rapport et retourner leurs digests/URI. |
| attente synchrone et sortie texte | `sandbox.get_execution` / `sandbox.cancel_execution` | Passer à un handle d'exécution, heartbeat, pagination bornée et état final explicite. |

`SandboxService` a été supprimé par MCP-089 après la campagne shadow et l'activation indépendante des cinq opérations. Une configuration sandbox `DIRECT`/`MCP_SHADOW`, un serveur désactivé ou une opération absente de l'allow-list échoue désormais explicitement, sans fallback Docker.

## 6. `GiteaService`

Source : `apps/orchestrator/src/main/java/com/example/aifactory/service/GiteaService.java`.

| Appel/comportement actuel | Outil cible | Décision de migration |
|---|---|---|
| réception et analyse d'une `repositoryUrl` arbitraire | `scm.get_repository(repository_id)` | Remplacer l'URL utilisateur par un registre de dépôts allow-listés. |
| `git checkout -b`, configuration d'identité, `git add/reset/commit` | `scm.create_draft_pull_request` | Garder ces opérations privées dans le serveur SCM ; aucun outil bas niveau public. |
| création d'une URL distante contenant utilisateur et jeton | secret interne du serveur SCM | Supprimer immédiatement ce mode lors de la bascule ; le jeton ne doit jamais apparaître dans une commande ou une URL. |
| `git push` | `scm.create_draft_pull_request` | Exécuter dans la transaction idempotente de livraison avec branche cible protégée. |
| POST Gitea `/api/v1/repos/{owner}/{repo}/pulls` | `scm.create_draft_pull_request` | Vérifier source SHA, digests de preuves, gates, approbation et clé d'idempotence avant l'effet. |
| lecture de l'URL publique de PR | réponse de `scm.create_draft_pull_request`, puis `scm.get_pull_request` | Retourner ID et URL normalisés sans exposer l'adressage interne. |

Le serveur n'expose volontairement aucun outil `merge`, `force_push`, `delete_branch`, `write_file` ou `run_git`. `GiteaService` est retiré après la bascule stable prévue par MCP-123.

## 7. Écarts de contrat à résoudre

| ID | Écart | Risque si ignoré | Décision attendue |
|---|---|---|---|
| E1 | Le catalogue ne définit pas encore la matérialisation initiale d'un dépôt autorisé dans un workspace. | Conservation d'un `git clone` réseau et d'une URL arbitraire dans l'orchestrateur. | Définir une capacité bornée de préparation de source, adossée à `repository_id` et au SHA produit par `scm.resolve_revision`, sans commande Git libre. Décider si elle appartient au serveur SCM ou à un gestionnaire de workspace interne. |
| E2 | `evidence.register` attend une URI de staging, mais le producteur et le protocole de staging du patch/plan/rapports ne sont pas définis. | Dépendance persistante au volume partagé et confiance implicite dans un chemin local. | Définir upload borné ou URI de staging à usage unique, vérification du digest, taille/type autorisés et expiration. |
| E3 | Le contrat commun mentionne la tâche mais pas systématiquement la tentative. | Mélange ou rejeu de résultats entre deux exécutions d'une même tâche. | Rendre `attempt_id` obligatoire dans les handles, workspaces, preuves, idempotency keys et journaux. |
| E4 | L'état des tâches et la file d'exécution sont uniquement en mémoire. | Perte de reprise après redémarrage ; ce n'est pas un accès outil. | Traiter dans l'industrialisation de l'orchestrateur, sans créer un serveur MCP artificiel. |

E1 à E3 doivent être intégrés aux schémas communs avant la suppression des implémentations directes correspondantes.

## 8. Séquence de retrait des accès directs

| Ordre | Accès direct retiré | Condition minimale |
|---:|---|---|
| 1 | lectures de `RepositoryContextService` | parité `MCP_SHADOW`, limites/redaction testées, activation `MCP_ACTIVE` stable ; MCP-057 |
| 2 | `docker run`, commandes de build et jetons d'analyse dans l'orchestrateur | profils allow-listés, handles, preuves, annulation et tests négatifs ; MCP-089 |
| 3 | jeton Gitea, commandes Git de livraison et API PR | registre de dépôts, approbation vérifiable, idempotence et audit ; MCP-123 |
| 4 | écritures locales servant de contrat interservices | staging immuable et manifeste par tentative disponibles dans `evidence-mcp` |

Pour chaque retrait, la bascule est effectuée par opération, avec les états `DIRECT`, `MCP_SHADOW` et `MCP_ACTIVE`. En `MCP_ACTIVE`, une indisponibilité ou une preuve indéterminée échoue de façon fermée ; elle ne réactive pas silencieusement l'accès direct.

## 9. Critères de clôture de MCP-002

- [x] Les appels directs des quatre services sont inventoriés.
- [x] Chaque capacité a une destination MCP ou une décision explicite de rester dans l'orchestrateur.
- [x] Les mutations et secrets actuellement détenus par l'orchestrateur sont identifiés.
- [x] Les écarts empêchant la suppression complète des accès directs sont documentés.
- [x] Les jalons de retrait et leurs conditions sont rattachés au plan MCP.
