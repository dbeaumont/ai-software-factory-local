# Backlog et pistes d'évolution

> État revu le 5 septembre 2026. Les éléments « faits » attestent une présence dans le dépôt ; ils ne valent pas
> qualification de production. Voir la [rétrodocumentation](../overview/current-state.md) pour la matrice actif/disponible/cible.

## Fait

- [x] Point d'entrée HTTP avec reverse proxy Nginx (`/` vers `factory-web`, `/api/` vers `orchestrator`).
- [x] Intégration SonarQube et miroir Artifactory.
- [x] Scans déterministes : SBOM Syft CycloneDX et vulnérabilités/secrets Trivy.
- [x] Boucle bornée de réparation du patch lorsque `git apply --check` échoue.
- [x] Migration vers Java 25, Spring Boot 4.1.1 et Spring AI 2.0.1.
- [x] Cinq serveurs MCP séparant contexte, sandbox, SCM, assurance et preuves.
- [x] Métriques, traces et logs OpenTelemetry, sept dashboards SigNoz et règles d'alerte métier/techniques.
- [x] Diagrammes d'architecture du pipeline, des agents, des données, de la confiance et de la cible GCP.
- [x] Implémentations Temporal, DAG multi-agent, contrats et politiques de qualification disponibles dans le code.

## Prochaines étapes produit et plateforme

- [ ] Formaliser les contrats de tickets avec OpenAPI et, si retenu, OpenSpec/SpecKit.
- [ ] Intégrer Keycloak ou un fournisseur OpenID Connect pour l'authentification et le RBAC.
- [ ] Ajouter un déclenchement par GitHub Actions/GitLab CI sans le confondre avec le backend d'exécution sandbox.
- [ ] Persister les tâches et journaux d'audit dans un stockage de contrôle durable.
- [x] Retirer l'accès du contrôleur sandbox à `/var/run/docker.sock` avec des runners Compose statiques en local
  et des Jobs GKE isolés en environnement partagé ; suivre le
  [plan détaillé de migration](../migrations/retrait-docker-socket.md).
- [x] Exporter les six applications en OTLP via le Collector et corréler métriques, traces et logs dans SigNoz.
- [ ] Compléter les écrans de supervision fonctionnelle, technique et FinOps.
- [ ] Ajouter une commande opérateur et une vue IHM pour le kill switch.
- [x] Instrumenter Assurance, Evidence et SCM MCP et exporter leurs trois signaux en OTLP.
- [ ] Qualifier puis activer progressivement Temporal et le mode hiérarchique.
- [ ] Valider sauvegarde, restauration, rétention et purge de bout en bout.

## Écarts de l'interface d'exploitation

### P0 corrigé — `make urls` n'expose plus les secrets d'observabilité

Le [`Makefile`](../../../Makefile) n'affiche que les URLs et les identifiants non sensibles. Les secrets restent
dans `.env`/`.vault` et ne doivent jamais être ajoutés à une cible d'aide ou à un journal de CI.

### P1 — aucune cible Make n'active l'architecture hiérarchique

Les seules campagnes exposées sont `mcp-shadow-campaign` et `mcp-active-campaign`, consacrées à la migration MCP
historique des rôles `planner`, `developer` et `patch-repair`. Il manque notamment :

- `multiagent-shadow` ;
- `multiagent-qualification` ;
- `multiagent-canary` ;
- `multiagent-status` ;
- `multiagent-rollback`.

L'API de création ne permet pas de sélectionner directement un mode hiérarchique :
[`TaskRequest.java`](../../apps/orchestrator/src/main/java/com/example/aifactory/model/TaskRequest.java) force aujourd'hui
le mode LLM cloud et ne porte pas de mode d'exécution multi-agent.

### P1 — Temporal est démarré et son raccordement reste à terminer

`make up` démarre Temporal et sa base. Cependant :

- le sélecteur historique `AI_FACTORY_TEMPORAL_ENABLED` a été retiré afin d'imposer un moteur unique ;
- le démarrage de l'orchestrateur en mode pipeline ne dépend volontairement pas de la readiness Temporal ;
- [`DeterministicWorkflowCoordinator`](../../apps/orchestrator/src/main/java/com/example/aifactory/service/DeterministicWorkflowCoordinator.java)
  reste l'implémentation active de `WorkflowCoordinator`.

Le démarrage de la stack ne signifie donc pas que le chemin public utilise Temporal.

### P1 — kill switch non raccordé au déploiement local

La propriété `AI_FACTORY_MCP_KILL_SWITCH_FILE` existe dans [`.env.example`](../../.env.example) et le code applique
les décisions de `OperationalKillSwitch`. Compose ne transmet toutefois pas cette variable, ne monte aucun fichier
de contrôle et le Makefile n'expose aucune commande de vérification ou d'activation.

### P2 — `make build` ne reconstruit pas explicitement LiteLLM

LiteLLM possède un contexte `build`, mais ne figure pas dans la liste explicite de services passée à `docker
compose build` par le [`Makefile`](../../Makefile). Une modification de son Dockerfile ou de sa configuration n'est
donc pas reconstruite par cette cible.

### P2 — une commande de campagne documentée n'existe pas

[`BASELINE-PIPELINE-V1.md`](../qualification/multi-agents/baselines/BASELINE-PIPELINE-V1.md) demandait auparavant
`make mcp-agent-ab-campaign`, mais aucune cible Make de ce nom n'existe. La documentation utilise désormais le
script réel `./scripts/mcp-agent-ab-campaign.sh` ; une cible Make dédiée reste souhaitable pour l'ergonomie.

### P3 — commandes opérateur centrées sur le pipeline

- `make restart` et `make logs` ne ciblent que l'orchestrateur ;
- `make urls` n'affiche pas Temporal UI ;
- les routes `approve-manifest`, annulation, décisions, retry et fallback ne sont pas présentées ;
- l'aide de `make build` ne cite pas tous les composants effectivement construits.

## Contrôles de cohérence

- `make config` était déclaré en succès lors de la précédente vérification et `docker compose config --quiet`
  reste syntaxiquement valide lors de la revue documentaire du 3 septembre 2026.
- Les rapports Surefire présents couvrent 158 classes et 499 cas, avec 0 échec, 0 erreur et 1 cas ignoré. Cette
  lecture d'artefacts ne remplace pas une nouvelle exécution de `make test`.
- Les cinq serveurs MCP possèdent des tests.
- Les campagnes MCP shadow et active restent des preuves historiques versionnées.
- `clean` est correctement présenté comme destructif.
