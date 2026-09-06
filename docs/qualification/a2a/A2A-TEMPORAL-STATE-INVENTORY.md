# A2A-022 — Inventaire des états Temporal et correspondance A2A

## Règle de migration

La migration conserve les décisions et les frontières durables de Temporal. Elle remplace seulement le travail
d'agent effectué dans la JVM par la séquence d'activités A2A `resolveAgent` → `dispatchTask` → attente signalée
avec `getTask` de réconciliation → `validateArtifacts`, et par `cancelTask` lors d'une annulation. Les opérations
de source, sandbox, assurance, Evidence et SCM restent des activités Temporal/MCP et ne deviennent pas A2A.

## Workflows

| Workflow ou composant déterministe actuel | État actuel | Correspondance après migration | Invariant conservé |
|---|---|---|---|
| `SoftwareFactoryExecutionWorkflowV1` | Workflow racine de production, pinned, admission, source, pipeline, gates, signaux et livraison | Nouveau type/version de workflow racine A2A ; même autorité sur le DAG et mêmes signaux | Temporal reste l'unique autorité ; aucun appel réseau dans le code de workflow |
| `SoftwareFactoryWorkflowImpl` | Delegate déterministe du workflow racine pour DAG, décisions, approbation et `continue-as-new` | Logique conservée dans la version A2A du workflow racine | Ordre, budgets, fan-out, dépendances, gates et chronologie inchangés |
| `DelegationWorkflow` | Child workflow par nœud ; valide l'identité puis retourne actuellement `READY_FOR_ACTIVITIES` | Child workflow de contrôle exécutant les activités A2A et attendant notifications/timers | Même workflow ID déterministe et même propagation `FAILED`, `TIMED_OUT`, `CANCELLED`, `INDETERMINATE` |
| `IndependentReviewWorkflow` | Child distinct lancé après consolidation ; retourne actuellement `READY_FOR_ACTIVITIES` | Child de contrôle qui adresse exclusivement le skill de `independent-reviewer` par A2A | Revue après consolidation, budget propre, bundle lié aux digests, aucune donnée privée des spécialistes |
| `PatchIntegrationWorkflow` | Workflow durable d'application, vérification et nettoyage de patchs | Conservé ; il consomme les références de patchs validées reçues via A2A | Profils sandbox imposés, ordre apply/verify/cleanup et cleanup détaché inchangés |
| `AgentTaskWorkflowV1` | Absent | Nouveau workflow côté runtime d'agent, démarré une fois par tâche A2A | Exécute un seul rôle ; ne prend aucune décision de délégation globale |

`DelegationScheduler` reste responsable du tri topologique, des batches parallèles (maximum 4), de la profondeur,
du fan-out, des budgets, du `continue-as-new` et de la propagation des blocages. Le serveur A2A ne reçoit aucune
capacité de réordonner ou de créer des nœuds du DAG.

## Activités enregistrées aujourd'hui

| Activité Temporal | Queue actuelle | Usage | Traitement cible |
|---|---|---|---|
| `ResolveAndAttestSource` | `context` | Clone/résolution et attestation du commit source | Conservée hors A2A |
| `BindResolvedSource` | `context` | Projection du workspace et du commit attesté | Conservée hors A2A |
| `ExecutePipelineStep(plan)` | `llm` | Invocation directe du planificateur | Remplacée par le child de contrôle et `dispatchTask(supervisor, plan)` |
| `GeneratePatchCandidate` | `llm` | Invocation directe de génération | Remplacée par `dispatchTask` vers le rôle/skill de code déterminé par le DAG |
| `RepairPatchCandidate` | `llm` | Invocation directe de réparation, maximum deux reprises métier | Remplacée par une nouvelle tâche A2A `patch-repair` décidée par Temporal ; même borne métier |
| `ExecutePipelineStep(review)` | `llm` | Invocation directe du reviewer du pipeline | Remplacée par l'interaction A2A `independent-reviewer` après consolidation |
| `ExecutePipelineStep(test)` | `sandbox` | Mélange actuel d'instruction de test et d'exécution sandbox | Instruction via A2A `test-agent`; exécution d'outil via MCP/sandbox sous contrôle de l'agent |
| `ExecutePipelineStep(apply-patch)` | `sandbox` | Application du patch candidat | Conservée comme effet sandbox de contrôle ; peut être absorbée par `PatchIntegrationWorkflow` |
| `ExecutePipelineStep(quality/security)` | `assurance` | Gates d'assurance déterministes | Conservées hors A2A ; les analyses d'agent produisent seulement des artefacts d'entrée |
| `ApplyPatchIntegration` | `sandbox` | Application idempotente de patchs validés | Conservée hors A2A |
| `VerifyPatchIntegration` | `sandbox` | Tests, qualité et sécurité après intégration | Conservée hors A2A |
| `CleanupPatchIntegration` | `sandbox` | Nettoyage terminal même après annulation | Conservée hors A2A |
| `CreatePipelineApprovalManifest` | `evidence` | Manifeste digesté de gate | Conservée hors A2A |
| `RecordPipelineGateRejection` | `evidence` | Projection d'un rejet métier | Conservée hors A2A |
| `RecordPipelineCancellation` | `evidence` | Preuve d'annulation | Conservée ; appelée après propagation A2A bornée |
| `RecordPipelineApproval` | `evidence` | Preuve d'approbation liée au manifeste | Conservée hors A2A |
| `RecordPipelineHumanDecision` | `evidence` | Preuve de décision humaine | Conservée hors A2A |
| `PreparePipelineDelivery` | `scm` | Prépare l'effet et la gate | Conservée hors A2A |
| `DeliverPipelinePullRequest` | `scm` | Effet SCM après approbation | Conservée hors A2A |

## Activités A2A à introduire côté contrôle

| Activité cible | Effet | Retry/ambiguïté | État durable produit |
|---|---|---|---|
| `ResolveAgent` | Résout et vérifie la carte allow-listée du rôle | Lecture retryable ; carte invalide non retryable | digest de carte, endpoint, binding et skill validés |
| `DispatchA2aTask` | Envoie `SendMessage` asynchrone avec un `messageId` stable | Un timeout est ambigu : réconciliation obligatoire avant renvoi | association message/tâche/contexte persistée |
| `GetA2aTask` | Lit les transitions manquantes | Lecture retryable et périodique | séquence d'états ordonnée et dernière version |
| `CancelA2aTask` | Propage une annulation demandée par le workflow | Idempotente ; état terminal traité comme convergence | annulation confirmée ou état explicite à réconcilier |
| `ValidateA2aArtifacts` | Vérifie contrat, références Evidence et digests | Échec de contrat non retryable | artefact principal accepté ou rejet fail-closed |

Ces activités effectuent les I/O. Le workflow ne manipule que des identifiants, versions, états, digests et URI
bornées. La notification A2A authentifiée est projetée puis traduite en signal Temporal idempotent ; `Workflow.await`
et un timer de réconciliation remplacent toute attente bloquante.

## États et points d'attente conservés

| État de contrôle existant | Déclencheur actuel | Déclencheur A2A cible |
|---|---|---|
| `RUNNING` / délégation pending | child workflow créé | carte vérifiée et message prêt à envoyer |
| `DELEGATION_COMPLETED` | résultat du child | tâche `COMPLETED` et artefacts validés |
| `DELEGATION_FAILED` | exception/statut du child | `FAILED`/`REJECTED` classé selon la même politique métier |
| `DELEGATION_BLOCKED` | dépendance terminale bloquante | propagation identique depuis le résultat A2A classé |
| `WAITING_HUMAN_DECISION` | `Workflow.await` sur signal | `INPUT_REQUIRED` autorisé devient une demande contrôlée et un signal |
| `WAITING_APPROVAL` | manifeste créé puis signal `approve` | inchangé ; aucun agent ne peut auto-approuver |
| `CANCELLED` | signal racine puis résultat d'annulation | signal racine, `CancelTask` sur chaque tâche active, attente bornée et preuve |
| `APPROVED` / `PR_CREATED` | signal lié au manifeste puis activité SCM | inchangé et toujours hors de l'autorité des agents |

## Éléments latents à supprimer ou réaffecter

- `DurableExecutionActivities.InvokeAgent` et `DurableExecutionActivitiesImpl.invokeAgent` appellent directement
  `AgentRuntime`. Ils ne sont pas enregistrés par `TemporalActivityAdapters` en production et doivent être
  supprimés lors de la coupure.
- `InvokeMcpTool` et `StoreEvidence` du même adaptateur ne sont pas enregistrés non plus. Les appels MCP côté
  runtime d'agent seront exécutés par les workers du rôle ; les effets de contrôle restent dans les activités
  spécialisées existantes.
- `PatchIntegrationWorkflow` est enregistré mais n'est pas lancé par le workflow racine actuel. La migration doit
  soit le raccorder explicitement après réception des patchs A2A, soit supprimer cette registration ; aucun chemin
  dormant ne doit rester ambigu à la gate de coupure.

## Contrôles de non-régression

L'inventaire est fondé sur les annotations `@WorkflowMethod`, `@SignalMethod`, `@QueryMethod`, `@ActivityMethod`,
les créations de stubs et les registrations de `TemporalWorkerRegistry`/`TemporalActivityAdapters`. Les tests
`DelegationSchedulerTest`, `SoftwareFactoryWorkflowTest`, `TemporalFailureModesTest` et
`TemporalCascadeCancellationTest` constituent les oracles pour l'ordre, les statuts, retries et annulations ; ils
ont été exécutés avec succès dans A2A-021 et devront rester verts après chaque remplacement.
