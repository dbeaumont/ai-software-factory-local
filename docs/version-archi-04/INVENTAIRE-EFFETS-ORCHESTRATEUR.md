# Inventaire des effets de l'orchestrateur

État de référence : lot 2 de la migration vers l'architecture multi-agent hiérarchique.

Cet inventaire distingue la décision de workflow, les ports appelés et les adaptateurs qui réalisent
effectivement l'entrée-sortie. Un appel en lecture reste un effet : son résultat dépend d'un système externe
ou d'un état mutable.

## Effets déclenchés par le workflow

| Effet | Déclencheur actuel | Port ou frontière | Adaptateur effectif | Contrainte cible |
|---|---|---|---|---|
| Planification asynchrone | `DeterministicWorkflowCoordinator.start` et `resumeAfterApproval` | `WorkflowCoordinator` | `ExecutorService` local | Remplacé par l'historique et les workers Temporal. |
| Création du workspace | `runPipeline` | à extraire avec les activités | `Files.createDirectories` | Activité idempotente, chemin borné à la tâche. |
| Clone et résolution du commit | `runPipeline` | `ProcessRunner` | processus `git` | Activité ; le commit résolu devient immuable. |
| Collecte du contexte | `runPipeline` et réparation | `RepositoryContextProvider` | `McpRepositoryContextService` puis Context MCP | Lecture bornée au rôle et au commit. |
| Inférence LLM | `chat` | `LlmGatewayClient` / `AgentRuntime` | `WebClient` vers la passerelle LLM | Activité ; sortie validée avant persistance. |
| Écriture plan, revue et métadonnées | `runPipeline`, `writeRunMetadata` | fichiers locaux transitoires | `Files.writeString` | Activité puis dépôt immuable via `EvidenceRepository`. |
| Validation du patch | `validateAndRepairPatch` | `PatchIntegrator` | `SandboxExecutor.checkPatch` | Seul le workflow demande la validation ; digest conservé. |
| Application du patch | `runPipeline` | `PatchIntegrator` | `SandboxExecutor.applyPatch` | Seul le workflow demande l'application après validation du digest. |
| Tests déterministes | `runPipeline` | `SandboxExecutor.test` | Sandbox Execution MCP | Activité avec identifiant d'exécution et idempotence. |
| Analyse qualité | `runPipeline` | `SandboxExecutor.quality`, puis `AssuranceGateway` | Sandbox et Assurance MCP | Activités séparées ; la gate consomme une preuve vérifiée. |
| Analyse sécurité et lecture SBOM | `runPipeline` | `SandboxExecutor.security` | Sandbox MCP et `Files.readAllBytes` | Activité ; artefacts stockés par `EvidenceRepository`. |
| Livraison SCM | `resumeAfterApproval` | `ScmDeliveryGateway` | SCM MCP | Activité après décision humaine liée au manifeste. |
| État et métriques du run | coordinateur et `TaskState` | `TaskMemory`, compteurs Micrometer | mémoire locale / registre métrique | État durable via Temporal ; métriques sans donnée sensible. |

## Effets hors décision métier du workflow

| Zone | Effet observé | Justification et traitement |
|---|---|---|
| `TaskService` | admission, `TaskMemory.save`, métrique de soumission et commande d'approbation | Façade API autorisée ; elle ne doit appeler ni sandbox, ni assurance, ni SCM. |
| `AgentRuntime` / `AgentToolLoop` | appel du modèle et exécution d'outils injectés | Outils en lecture seulement ; aucune dépendance directe vers sandbox, assurance ou SCM. |
| `AgentContextToolHost` | `context.*` via MCP | Lecture de contexte explicitement autorisée par rôle, tâche et commit. |
| `PatchIntegrator` | écriture/lecture de `changes.patch`, validation et application par le port sandbox | Service déterministe piloté par le coordinateur ; aucun appel LLM. |
| `McpRepositoryContextService` | appels `context.list_tree` et `context.read_file` | Adaptateur du port de contexte. |
| `McpSandboxService` | démarrage et consultation d'exécutions sandbox, lecture du digest de patch | Adaptateur du port `SandboxExecutor`. |
| `AssuranceGateway` | `assurance.evaluate_quality_gate` | Adaptateur de gate déterministe. |
| `ScmDeliveryGateway` | lecture des artefacts et `scm.create_draft_pull_request` | Adaptateur de livraison, protégé par l'approbation. |
| chaîne `SpringMcpToolInvoker` → `ValidatedMcpToolInvoker` → `ResilientMcpToolInvoker` | transport réseau, validation, timeout, retry, circuit breaker et métriques | Infrastructure MCP partagée ; les outils à effet exigent une clé d'idempotence. |
| `LlmGatewayClient` | appels HTTP et vérification de disponibilité | Adaptateur LLM ; ne porte aucune décision de workflow. |
| `ProcessRunner` | création et interruption de processus locaux | Infrastructure d'activité locale, à ne jamais injecter dans un agent. |
| `PromptService` | lecture des prompts versionnés | Lecture locale bornée aux ressources de prompts. |
| `OperationalKillSwitch` | lecture du fichier de contrôle | Effet opérateur global, évalué avant les appels MCP concernés. |
| `McpServerRegistry` et indicateurs de santé | négociation et vérification de disponibilité | Effets de supervision hors exécution métier. |

## Frontières vérifiables pour la suite

- les décisions d'appeler validation/application, tests, qualité, sécurité, assurance et SCM appartiennent au
  `WorkflowCoordinator` ;
- `PatchIntegrator` est le seul service déterministe autorisé à traduire une décision du workflow en
  validation ou application de patch ;
- les agents ne reçoivent que le modèle, leurs outils de contexte filtrés et les contrats ;
- `TaskService` reste limité à l'admission, la consultation et les commandes ;
- les clients réseau, processus et accès fichiers sont des adaptateurs ou des activités, jamais du code de
  workflow Temporal déterministe.

Cet inventaire sert de base aux tests d'architecture MAH-051 à MAH-053 et à l'extraction des activités Temporal.
