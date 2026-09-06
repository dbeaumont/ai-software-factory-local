# Inventaire des appels d'agents avant migration A2A

- Ticket : A2A-020
- Baseline : `b6dd43b1f1bc14aaa940f569c564e78a992a8a37`
- Date : 2026-09-06
- Portée : `apps/orchestrator/src/main/java`

## Synthèse

La production possède deux familles d'invocations à remplacer :

1. le pipeline racine appelle directement le LLM dans `PipelineStepService` pour les rôles de compatibilité
   `planner`, `developer`, `tester` et `reviewer` ;
2. `DurableExecutionActivitiesImpl` appelle directement `AgentRuntime`, chemin latent enregistré comme bean mais
   non raccordé aux workers par `TemporalActivityAdapters`.

Les façades hiérarchiques spécialisées appellent elles aussi `AgentRuntime`, mais ne sont actuellement exercées
que par leurs tests unitaires. Les child workflows de délégation valident le DAG et retournent
`READY_FOR_ACTIVITIES`; ils n'exécutent pas encore les agents de domaine.

Le contrôleur `FactoryController` et `TaskService` utilisent `LlmGatewayClient` pour la disponibilité et le choix
du mode LLM, pas pour une communication inter-agents. Ils ne sont pas des cibles A2A.

## Chemins de production actifs

| Appelant | Appelé directement | File/activité Temporal | Contrat actuel | Timeout/retry actuel | Cible A2A |
|---|---|---|---|---|---|
| `PipelineStepService.plan` | `LlmGatewayClient` avec prompt `planner` | `llm` / `ExecutePipelineStep` | plan de pipeline validé par `AgentResponseValidator`, preuve `PipelineStepContracts.Result` | activité LLM : schedule 20 min, start 10 min, 2 tentatives ; client 10 min | `architecture-agent`, skill de planification compatible |
| `PipelineStepService.generatePatch` | `LlmGatewayClient` avec prompt `developer` | `llm` / `GeneratePatchCandidate` | patch candidat puis référence Evidence | même profil LLM | `developer`, skill de proposition de patch |
| `PipelineStepService.repairPatch` | `LlmGatewayClient` avec prompt `developer` | `llm` / `RepairPatchCandidate` | patch réparé, erreur de validation liée | même profil LLM, boucle workflow bornée | `patch-repair`, skill de réparation ciblée |
| `PipelineStepService.test` | `LlmGatewayClient` avec prompt `tester` | `llm` / `ExecutePipelineStep` | analyse de tests plus preuves sandbox | même profil LLM | `test-agent`, skill d'évaluation de tests |
| `PipelineStepService.review` | `LlmGatewayClient` avec prompt `reviewer` | `llm` / `ExecutePipelineStep` | décision de revue et findings | même profil LLM | `independent-reviewer`, skill de revue finale |

Les étapes déterministes de contexte, application de patch, tests, qualité, sécurité, Evidence et SCM restent des
activités Temporal/MCP. Elles ne deviennent pas des agents A2A.

## Chemin direct latent

| Appelant | Appelé | Entrée | Sortie | État de câblage | Remplacement |
|---|---|---|---|---|---|
| `DurableExecutionActivitiesImpl.invokeAgent` | `AgentRuntime.execute` | `DurableExecutionActivities.AgentCall`, contenant `AgentRuntime.Invocation` | `AgentResult` avec document, fingerprint et usage | bean Spring présent, mais absent de `TemporalActivityAdapters` | activités `resolveAgent`/`dispatchTask`/`getTask`/`validateArtifacts` |

Ce chemin doit être supprimé, et non réutilisé derrière un adaptateur A2A. Son contrat transporte directement les
types de l'ancien runtime et violerait l'isolation définie par `ADR-A2A-001`.

## Façades hiérarchiques actuellement locales

| Façade | Rôle(s) | Prompt(s) | Contrat(s) de sortie | Validations hôte à préserver | Cible A2A |
|---|---|---|---|---|---|
| `SupervisorAgent.execute` | `supervisor` | `supervisor` | `delegation-plan-v1`, `supervisor-decision-v1` | limites de délégation, consolidation, références | carte `supervisor`, skills `decompose`, `consolidate`, `replan` |
| `ArchitectureAgents.execute` | `architecture-agent`, `impact-analysis`, `dependencies-contracts` | nom du rôle | `architecture-assessment-v1`, `specialist-result-v1` | périmètre Architecture, outils `context.*` seulement | une carte et un endpoint par rôle |
| `CodeAgent.coordinate` | `code-agent` | `code-agent` | `integration-proposal-v1` | références autorisées et absence d'effet direct | carte `code-agent`, skill `coordinate-code` |
| `DeveloperAgent.execute` | `developer` | `developer-hierarchical` | `patch-proposal-v1` | `code-task-v1`, commit, worktree, scope et références | carte `developer`, skill `propose-patch` |
| `PatchRepairAgent.execute` | `patch-repair` | `patch-repair-hierarchical` | `patch-repair-proposal-v1` | tentative autorisée, cause, conflit et scope exacts | carte `patch-repair`, skill `repair-patch` |
| `TestAgents.execute` | `test-agent`, `test-design`, `test-evidence` | nom du rôle | `test-assessment-v1`, `test-strategy-v1` | stratégie, commit, patch et preuves d'exécution | une carte et un endpoint par rôle |
| `SecurityAgents.execute` | `security-agent`, `threat-model`, `security-findings` | nom du rôle | `security-assessment-v1` | findings normalisés, preuves et décisions de politique | une carte et un endpoint par rôle |
| `IndependentReviewerAgent.execute` | `independent-reviewer` | `independent-reviewer` | `independent-review-v1` | indépendance, manifeste final, résultats et contradictions | carte `independent-reviewer`, skill `independent-review` |

Ces façades sont des spécifications exécutables utiles à l'extraction de `agent-core`. Après la coupure, leurs
validateurs hôte se placent avant l'envoi A2A ou après réception d'artefact ; les façades Spring de
l'orchestrateur disparaissent.

## Sous-agents déclarés sans façade dédiée

Les rôles suivants sont déjà représentés dans les façades de périmètre et le catalogue, mais ne possèdent pas de
classe Java individuelle :

- `impact-analysis` et `dependencies-contracts` via `ArchitectureAgents` ;
- `test-design` et `test-evidence` via `TestAgents` ;
- `threat-model` et `security-findings` via `SecurityAgents`.

Ils doivent malgré tout obtenir chacun un endpoint, une carte et une identité A2A distincts.

## Entrée commune `AgentRuntime`

`AgentRuntime.Invocation` porte actuellement :

- `taskId`, `attemptId`, `sourceCommit` et identité d'exécution ;
- rôle, prompt, contrat de sortie et mode métier ;
- outils et références allow-listés ;
- entrée non fiable et budget tours/tokens/coût/délai.

`AgentRuntime.Result` retourne le document JSON validé, le fingerprint du prompt, le nombre de tours, les tokens
et le coût. Cette information doit être répartie entre :

- l'enveloppe d'instruction A2A ;
- l'extension de corrélation A2A ;
- l'artefact final structuré ;
- les attributs de télémétrie et le ledger d'usage.

Le runtime peut demander seulement les outils MCP résolus depuis l'allow-list du catalogue. Les outils à effet
`sandbox.*`, `assurance.*`, `scm.*`, `evidence.store` et `evidence.create_manifest` sont déjà refusés dans
`AgentRuntime` et doivent le rester dans les runtimes A2A.

## Erreurs et arrêts à mapper

| Erreur/arrêt local | Mapping A2A attendu | Retry Temporal |
|---|---|---|
| `SUCCESS_CRITERIA_MET` | `TASK_STATE_COMPLETED` avec artefact | non |
| `BUDGET_EXHAUSTED`, `DEADLINE_REACHED`, `NO_PROGRESS` | `TASK_STATE_FAILED` avec `ErrorInfo` stable | selon politique, sans nouvel effet aveugle |
| `BLOCKED` | `TASK_STATE_INPUT_REQUIRED` si une entrée autorisée peut débloquer, sinon `FAILED` | attente ou classification |
| `CANCELLED` | `TASK_STATE_CANCELED` | non |
| `CONTRACT_ERROR`, `POLICY_DENIED` | `TASK_STATE_REJECTED` | non retryable |
| `TOOL_ERROR` | `TASK_STATE_FAILED` avec catégorie MCP | selon caractère transitoire |
| dépendance indisponible/timeout | erreur système A2A ou `FAILED` réconciliable | borné par Temporal |

## Points de suppression vérifiables

Après la bascule, les recherches suivantes doivent retourner zéro chemin de production :

```shell
rg -n 'AgentRuntime|AgentExecutor' apps/orchestrator/src/main/java
rg -n 'LlmGatewayClient' apps/orchestrator/src/main/java/com/example/aifactory/service/PipelineStepService.java
rg -n 'SupervisorAgent|ArchitectureAgents|CodeAgent|TestAgents|SecurityAgents|IndependentReviewerAgent' \
  apps/orchestrator/src/main/java
```

Les occurrences restent autorisées uniquement dans le module `agent-core`, le runtime A2A et leurs tests.

## Couverture existante à préserver

- `AgentRuntimeTest` et `AgentRoleIsolationTest` pour la boucle, les contrats et les permissions ;
- tests unitaires propres à chaque façade de rôle ;
- `DurableExecutionActivitiesTest` pour les bindings d'identité ;
- `SoftwareFactoryWorkflowTest` et tests du scheduler pour DAG, budgets et propagation d'échec ;
- `PipelineCompatibilityTest` et tests `PipelineStepService` pour le comportement de référence ;
- tests Temporal de replay, reprise, annulation et modes de panne.

## Conclusion

L'inventaire ne révèle aucun appel réseau A2A existant. La migration doit remplacer le chemin de pipeline actif,
retirer le chemin `DurableExecutionActivities` latent et déplacer les façades hiérarchiques dans le runtime
indépendant. Les validations hôte restent dans le plan de contrôle ; seules les analyses de rôle traversent A2A.

