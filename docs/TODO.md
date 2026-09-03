# Backlog & Pistes d'évolution

## Fait

- [x] Point d'entrée HTTP avec reverse proxy Nginx (`reverse-proxy` relayant `/` vers `factory-web` et `/api/` vers `orchestrator`)
- [x] Intégration SonarQube et miroir Artifactory pour la qualité et la résolution d'artefacts
- [x] Scans de sécurité déterministes (SBOM Syft CycloneDX + Trivy vulnérabilités & secrets)
- [x] Boucle d'auto-réparation de diff (`PatchRepair`) lors de l'échec de `git apply --check`
- [x] Observabilité complète avec métriques Micrometer, Prometheus v3.5 et tableau de bord Grafana v12.1

## À étudier / Prochaines étapes

- [ ] Support d'OpenSpec / OpenAPI pour la définition formelle des contrats de tickets
- [ ] Intégration Keycloak / OpenID Connect pour l'authentification et le RBAC des utilisateurs
- [ ] Déclenchement de l'orchestration via GitHub Actions / GitLab CI au lieu du socket Docker
- [ ] Diagrammes d'architecture enrichis centrés sur les boucles de rétroaction agentiques
- [ ] Persistance des tâches et journaux d'audit dans une base de données de contrôle dédiée
- [ ] Suppression de l'accès direct à la socket docker
- [ ] Passer en Java 25 et SpringBoot 4.x
- [ ] Support de SpecKit pour la définition formelle des spécifications
- [ ] Support de OpenTelemetry pour la supervision
- [ ] Mise en place d'écrans de supervision : 
         - fonctionnelle : supervision des processus (ce qui a marché, interruptions et raisons)
         - technique : activité par agent (like top -o cpu)
         - finops : consommation par agent
- [ ] Kill switch dans l'IHM de supervision


où sont les agents ?
activer le mode pipeline dans compose / makefile
activer le kill switch
vérifier les variables de .env.exemple qui ne seraient pas présentes dans .env
ajouter les target du mode hiérarchique dans makefile
activer temporal



## Verdict

Le Makefile est syntaxiquement valide et sa cible de tests fonctionne, mais il n’est pas encore cohérent comme interface d’exploitation de l’architecture multi-agent hiérarchique.

### Points importants

1. **Critique — `make urls` expose des secrets**

Le Makefile affiche directement les mots de passe Gitea, SonarQube, Artifactory et Grafana : [Makefile](/Users/david/Dev/ai-software-factory-local/Makefile:164). Ils apparaissent aussi avec `make -n urls`.

Il faudrait n’afficher que les utilisateurs et indiquer où récupérer les secrets.

2. **Majeur — aucune cible n’active réellement l’architecture hiérarchique**

Les seules campagnes disponibles sont `mcp-shadow-campaign` et `mcp-active-campaign`, qui testent la migration MCP historique avec les rôles `planner`, `developer` et `patch-repair` : [Makefile](/Users/david/Dev/ai-software-factory-local/Makefile:121).

Il manque des cibles explicites comme :

- `multiagent-shadow`
- `multiagent-qualification`
- `multiagent-canary`
- `multiagent-status`
- `multiagent-rollback`

L’API de création ne permet d’ailleurs pas de sélectionner un mode hiérarchique : [TaskRequest.java](/Users/david/Dev/ai-software-factory-local/apps/orchestrator/src/main/java/com/example/aifactory/model/TaskRequest.java:3).

3. **Majeur — Temporal est démarré, mais désactivé côté orchestrateur**

`make up` démarre tous les services Compose, y compris Temporal, mais :

- `AI_FACTORY_TEMPORAL_ENABLED` vaut `false` par défaut : [compose.yaml](/Users/david/Dev/ai-software-factory-local/infrastructure/compose.yaml:407) ;
- l’orchestrateur ne dépend pas de la readiness de Temporal : [compose.yaml](/Users/david/Dev/ai-software-factory-local/infrastructure/compose.yaml:429) ;
- le seul `WorkflowCoordinator` de production trouvé reste `DeterministicWorkflowCoordinator` : [DeterministicWorkflowCoordinator.java](/Users/david/Dev/ai-software-factory-local/apps/orchestrator/src/main/java/com/example/aifactory/service/DeterministicWorkflowCoordinator.java:25).

Le Makefile donne donc l’impression de démarrer la cible 1.2.0, alors que le chemin opérationnel reste le pipeline.

4. **Majeur — le kill switch n’est pas raccordé à Compose**

La variable existe dans [.env.example](/Users/david/Dev/ai-software-factory-local/.env.example:108), mais elle n’est ni transmise à l’orchestrateur ni associée à un fichier monté dans [compose.yaml](/Users/david/Dev/ai-software-factory-local/infrastructure/compose.yaml:336).

Aucune cible Make ne permet non plus de vérifier ou d’actionner proprement ce coupe-circuit.

5. **Moyen — `make build` oublie LiteLLM**

LiteLLM possède un contexte `build`, mais ne figure pas dans la liste explicite de [Makefile](/Users/david/Dev/ai-software-factory-local/Makefile:66). Une modification de sa configuration ou de son image de base ne sera donc pas reconstruite par `make build`.

6. **Moyen — une commande documentée n’existe pas**

La baseline demande `make mcp-agent-ab-campaign` dans [BASELINE-PIPELINE-V1.md](/Users/david/Dev/ai-software-factory-local/docs/multiagents/BASELINE-PIPELINE-V1.md:31), et le script existe, mais aucune cible correspondante n’est définie.

7. **Mineur — commandes opérateur encore centrées sur l’ancien pipeline**

- `make restart` ne redémarre que l’orchestrateur.
- `make logs` ne suit que l’orchestrateur.
- `make urls` n’affiche pas Temporal UI.
- Les nouvelles API `approve-manifest`, annulation, décisions, retry et fallback ne sont pas présentées.
- Les descriptions de `make build` parlent encore uniquement de la sandbox et de l’orchestrateur.

### Contrôles réussis

- `make config` : succès.
- Toutes les cibles déclarées sont syntaxiquement valides.
- `make test` : succès, avec **491 tests**.
- Les cinq serveurs MCP sont couverts : Context, Sandbox, SCM, Assurance et Evidence.
- Les campagnes MCP shadow et active passent leur validation à blanc.
- `clean` est correctement présenté comme destructif.

Aucun fichier n’a été modifié durant cette vérification. Les corrections prioritaires seraient : supprimer l’exposition des secrets, raccorder réellement Temporal et le kill switch, puis créer des cibles distinctes pour shadow, qualification, canary et rollback hiérarchiques.