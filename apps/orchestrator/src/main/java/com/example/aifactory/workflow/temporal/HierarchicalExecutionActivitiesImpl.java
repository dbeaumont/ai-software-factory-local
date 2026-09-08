package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aEvidencePartFactory;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.service.IndependentReviewBundle;
import com.example.aifactory.service.MultiAgentContractValidator;
import com.example.aifactory.service.PatchIntegrator;
import com.example.aifactory.service.PatchProposalValidator;
import com.example.aifactory.service.PatchScopeValidator;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.TaskMemory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Evidence-backed implementation of the hierarchical specialist input boundary. */
@Component
public final class HierarchicalExecutionActivitiesImpl implements HierarchicalExecutionActivities {
    private static final Pattern DIFF_HEADER = Pattern.compile("diff --git a/([^\\s]+) b/([^\\s]+)");
    private static final Set<String> SPECIALIST_ROLES = Set.of(
            "supervisor", "architecture-agent", "code-agent", "test-design", "test-agent", "security-agent");
    private static final Set<String> FULL_REVIEW_ROLES = Set.of(
            "architecture-agent", "code-agent", "developer", "test-design", "test-agent", "security-agent");
    private static final Set<String> SHORT_REVIEW_ROLES = Set.of("supervisor", "developer");
    private final TaskMemory memory;
    private final EvidenceRepository evidence;
    private final MultiAgentContractValidator contracts;
    private final ObjectMapper mapper;
    private final Clock clock;

    public HierarchicalExecutionActivitiesImpl(TaskMemory memory, EvidenceRepository evidence,
                                               MultiAgentContractValidator contracts, ObjectMapper mapper) {
        this(memory, evidence, contracts, mapper, Clock.systemUTC());
    }

    HierarchicalExecutionActivitiesImpl(TaskMemory memory, EvidenceRepository evidence,
                                        MultiAgentContractValidator contracts, ObjectMapper mapper, Clock clock) {
        this.memory = memory;
        this.evidence = evidence;
        this.contracts = contracts;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public A2aContracts.Part prepareSpecialistTask(PrepareSpecialistTask request) {
        requireValid(request);
        TaskState state = memory.find(request.taskId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown hierarchical task"));
        if (!request.attemptId().equals(state.workflowAttemptId)
                || !request.sourceCommit().equals(state.sourceCommit)) {
            throw new SecurityException("Hierarchical task input is outside the projected workflow attempt");
        }
        try {
            Instant issuedAt = Instant.now(clock);
            ObjectNode document = mapper.createObjectNode();
            document.put("schema_version", "1");
            document.put("specialist_task_id", "specialist-" + request.nodeId());
            document.put("task_id", request.taskId());
            document.put("attempt_id", request.attemptId());
            document.put("delegation_plan_id", request.delegationPlanId());
            document.put("node_id", request.nodeId());
            document.put("parent_role", request.parentRole());
            document.put("role", request.role());
            document.put("source_commit", request.sourceCommit());
            document.put("risk_class", state.request.routingFacts().risk());
            document.put("objective", state.request.requirement());
            ArrayNode inputs = document.putArray("inputs");
            request.inputs().forEach(input -> inputs.addObject()
                    .put("kind", input.kind()).put("uri", input.uri()).put("digest", input.digest()));
            ObjectNode scope = document.putObject("scope");
            scope.put("repository_id", request.repositoryId());
            ArrayNode readPaths = scope.putArray("read_paths");
            request.readPaths().stream().sorted().forEach(readPaths::add);
            ArrayNode writePaths = scope.putArray("write_paths");
            request.writePaths().stream().sorted().forEach(writePaths::add);
            ArrayNode allowedTools = document.putArray("allowed_tools");
            request.allowedTools().stream().sorted().forEach(allowedTools::add);
            ObjectNode budget = document.putObject("budget");
            budget.put("max_turns", request.budget().maxTurns());
            budget.put("max_tokens", request.budget().maxTokens());
            budget.put("max_cost_micros", request.budget().maxCostMicros());
            budget.put("timeout_seconds", request.budget().timeoutSeconds());
            budget.put("max_tool_calls", Math.min(64, request.budget().maxTurns() * 4));
            ArrayNode successCriteria = document.putArray("success_criteria");
            request.successCriteria().forEach(successCriteria::add);
            document.put("stop_condition", "BLOCKED_OR_ESCALATE");
            document.putArray("required_approval_ids");
            document.put("deadline", issuedAt.plusSeconds(request.budget().timeoutSeconds()).toString());
            document.put("issued_at", issuedAt.toString());
            contracts.validate("specialist-task-v1", document,
                    new MultiAgentContractValidator.ContractContext(request.taskId(), request.attemptId(),
                            Set.of(request.delegationPlanId(), request.nodeId())));
            byte[] content = mapper.writeValueAsBytes(document);
            String digest = TemporalIds.sha256(new String(content, StandardCharsets.UTF_8));
            EvidenceRepository.StoredEvidence stored = evidence.store(new EvidenceRepository.StoreRequest(
                    request.taskId(), request.attemptId(), "specialist-task", "application/json",
                    content, digest, "workflow"));
            if (!digest.equals(stored.digest()) || !"COMPLETE".equals(stored.status())) {
                throw new SecurityException("Stored specialist task differs from its validated document");
            }
            return A2aEvidencePartFactory.reference(
                    "specialist-" + request.nodeId(), stored.uri(), stored.digest(),
                    "specialist-task-v1", stored.sizeBytes());
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Cannot materialize hierarchical specialist task", failure));
        }
    }

    @Override
    public AcceptedSpecialistResult acceptSpecialistResult(AcceptSpecialistResult request) {
        requireValid(request);
        TaskState state = memory.find(request.taskId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown hierarchical task"));
        if (!request.attemptId().equals(state.workflowAttemptId)
                || !request.sourceCommit().equals(state.sourceCommit)) {
            throw new SecurityException("Hierarchical result is outside the projected workflow attempt");
        }
        try {
            EvidenceRepository.RawEvidence raw = evidence.read(new EvidenceRepository.ReadRequest(
                    request.taskId(), request.attemptId(), request.reference().uri(),
                    "workflow", "hierarchical-specialist-result"));
            if (!request.reference().digest().equals(raw.digest())
                    || !"agent-result".equals(raw.type()) || !"COMPLETE".equals(raw.status())) {
                throw new SecurityException("Hierarchical specialist result changed after A2A validation");
            }
            var document = contracts.validate(request.contract(), mapper.readTree(raw.content()),
                    new MultiAgentContractValidator.ContractContext(
                            request.taskId(), request.attemptId(), request.allowedReferenceIds()));
            String documentId = switch (request.contract()) {
                case "delegation-plan-v1" -> document.path("plan_id").asText();
                case "architecture-assessment-v1" -> document.path("assessment_id").asText();
                case "integration-proposal-v1" -> document.path("proposal_id").asText();
                case "test-strategy-v1" -> document.path("strategy_id").asText();
                case "test-assessment-v1" -> document.path("assessment_id").asText();
                case "security-assessment-v1" -> document.path("assessment_id").asText();
                default -> throw new IllegalArgumentException("Unsupported hierarchical result contract");
            };
            if (documentId.isBlank()) throw new SecurityException("Hierarchical result lacks its document ID");
            String artifactId = request.role().replace("-agent", "") + "-result";
            state.recordArtifact(artifactId, artifactId, raw.status(), raw.classification(), raw.uri(),
                    raw.digest(), raw.content().length, true);
            if (request.activateAsCodePlan()) {
                state.transition(com.example.aifactory.model.TaskStatus.PLANNING,
                        "Hierarchical execution plan accepted");
                state.plan = new String(raw.content(), StandardCharsets.UTF_8);
            }
            if (Set.of("test-agent", "security-agent").contains(request.role())) {
                state.assuranceResults.put(request.role(), mapper.convertValue(document, java.util.Map.class));
            }
            memory.project("hierarchical-result:" + request.role() + ':' + raw.digest(), state);
            return new AcceptedSpecialistResult(documentId,
                    new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                            raw.uri(), raw.digest(), raw.content().length, raw.status(), "ACCEPTED"));
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Cannot accept hierarchical specialist result", failure));
        }
    }

    @Override
    public List<DeveloperTask> prepareDeveloperTasks(PrepareDeveloperTasks request) {
        requireValid(request);
        TaskState state = memory.find(request.taskId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown hierarchical task"));
        requireAttemptAndCommit(state, request.attemptId(), request.sourceCommit());
        try {
            var architecture = readAndValidate(request.taskId(), request.attemptId(),
                    request.architectureReference(), "architecture-assessment-v1",
                    Set.of("specialist-architecture"), "prepare-developer-tasks");
            var integration = readAndValidate(request.taskId(), request.attemptId(),
                    request.integrationReference(), "integration-proposal-v1",
                    Set.of(request.delegationPlanId(), "code", request.architectureAssessmentId()),
                    "prepare-developer-tasks");
            if (!request.architectureAssessmentId().equals(architecture.path("assessment_id").asText())
                    || !request.architectureAssessmentId().equals(
                    integration.path("architecture_assessment_id").asText())) {
                throw new SecurityException("Developer tasks are not bound to the accepted architecture");
            }
            Map<String, tools.jackson.databind.JsonNode> scopes = new LinkedHashMap<>();
            architecture.path("recommended_code_scopes").forEach(scope ->
                    scopes.put(scope.path("scope_id").asText(), scope));
            Map<String, tools.jackson.databind.JsonNode> tasks = new LinkedHashMap<>();
            Set<String> taskNodes = new java.util.LinkedHashSet<>();
            integration.path("developer_tasks").forEach(task -> {
                tasks.put(task.path("code_task_id").asText(), task);
                taskNodes.add(task.path("node_id").asText());
            });
            if (tasks.isEmpty() || tasks.size() > 4 || tasks.size() != integration.path("developer_tasks").size()) {
                throw new SecurityException("Integration proposal must contain one to four unique Developer tasks");
            }
            if (taskNodes.size() != tasks.size()) throw new SecurityException("Developer task nodes must be unique");
            List<DeveloperTask> prepared = new java.util.ArrayList<>();
            Set<String> completedTaskIds = new java.util.LinkedHashSet<>();
            for (tools.jackson.databind.JsonNode orderedId : integration.path("application_order")) {
                var task = tasks.remove(orderedId.asText());
                if (task == null) throw new SecurityException("Developer application order is incomplete or duplicated");
                java.util.LinkedHashSet<String> dependencies = new java.util.LinkedHashSet<>();
                task.path("depends_on_task_ids").forEach(value -> dependencies.add(value.asText()));
                if (!completedTaskIds.containsAll(dependencies)) {
                    throw new SecurityException("Developer application order violates task dependencies");
                }
                var scope = scopes.get(task.path("scope_id").asText());
                if (scope == null) throw new SecurityException("Developer task references an unknown Code scope");
                prepared.add(storeDeveloperTask(request, state, task, scope));
                completedTaskIds.add(orderedId.asText());
            }
            if (!tasks.isEmpty()) throw new SecurityException("Developer application order omits a Code task");
            return List.copyOf(prepared);
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Cannot materialize hierarchical Developer tasks", failure));
        }
    }

    @Override
    public List<DeveloperTask> prepareShortDeveloperTasks(PrepareShortDeveloperTasks request) {
        requireValid(request);
        TaskState state = memory.find(request.taskId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown hierarchical task"));
        requireAttemptAndCommit(state, request.attemptId(), request.sourceCommit());
        try {
            var plan = readAndValidate(request.taskId(), request.attemptId(), request.planReference(),
                    "delegation-plan-v1", Set.of("specialist-short-plan"), "prepare-short-developer-task");
            if (!request.delegationPlanId().equals(plan.path("plan_id").asText())
                    || !request.sourceCommit().equals(plan.path("source_commit").asText())
                    || !state.request.routingFacts().risk().equals(plan.path("risk_class").asText())) {
                throw new SecurityException("Short Developer task is not bound to the accepted Supervisor plan");
            }
            if (plan.path("nodes").size() != 1 || !"developer".equals(plan.path("nodes").get(0).path("role").asText())) {
                throw new SecurityException("Short path Supervisor plan must contain exactly one Developer node");
            }
            var node = plan.path("nodes").get(0);
            if (!node.path("parent_node_id").isNull() || !node.path("depends_on").isEmpty()
                    || !request.repositoryId().equals(node.path("scope").path("repository_id").asText())) {
                throw new SecurityException("Short path Developer node exceeds its repository or dependency boundary");
            }
            DelegationWorkflow.Budget nodeBudget = boundedBudget(node.path("budget"), request.budget());
            return List.of(storeDeveloperTask(request.taskId(), request.attemptId(), request.repositoryId(),
                    request.sourceCommit(), request.delegationPlanId(), null, nodeBudget, state,
                    node.path("node_id").asText(), "code-" + node.path("node_id").asText(), node,
                    node.path("scope"), "depends_on", "success_criteria"));
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Cannot materialize short-path Developer task", failure));
        }
    }

    @Override
    public AcceptedDeveloperPatches acceptDeveloperPatches(AcceptDeveloperPatches request) {
        requireValid(request);
        TaskState state = memory.find(request.taskId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown hierarchical task"));
        requireAttemptAndCommit(state, request.attemptId(), request.sourceCommit());
        try {
            PatchProposalValidator validator = new PatchProposalValidator(new PatchScopeValidator());
            StringBuilder consolidated = new StringBuilder();
            List<ReviewedSpecialistResult> reviewed = new java.util.ArrayList<>();
            Set<String> proposalIds = new java.util.LinkedHashSet<>();
            for (DeveloperPatchResult result : request.results()) {
                var inputReference = specialistReference(result.task().inputReference());
                var codeTask = readCodeTask(request.taskId(), request.attemptId(), inputReference);
                var proposal = readAndValidate(request.taskId(), request.attemptId(), result.resultReference(),
                        "patch-proposal-v1", Set.of(result.task().codeTaskId(), result.task().nodeId()),
                        "accept-developer-patch");
                requireDeveloperBinding(result.task(), codeTask, proposal, request.sourceCommit());
                String rawPatch = proposal.path("patch").asText();
                PatchProposalValidator.ValidatedPatch validated = validator.validate(codeTask, proposal, rawPatch);
                byte[] content = validated.content().getBytes(StandardCharsets.UTF_8);
                String expectedUri = "evidence://" + request.taskId() + '/' + request.attemptId()
                        + "/code-patch/" + validated.digest();
                String declaredUri = proposal.path("diff_artifact").path("uri").asText();
                if (!expectedUri.equals(declaredUri)) {
                    throw new SecurityException("Developer patch declares a non-canonical Evidence URI");
                }
                EvidenceRepository.StoredEvidence stored = evidence.store(new EvidenceRepository.StoreRequest(
                        request.taskId(), request.attemptId(), "code-patch", "text/x-diff",
                        content, validated.digest(), "workflow"));
                if (!validated.digest().equals(stored.digest()) || !declaredUri.equals(stored.uri())
                        || !"COMPLETE".equals(stored.status()) || stored.sizeBytes() != content.length) {
                    throw new SecurityException("Developer patch Evidence differs from the validated proposal");
                }
                consolidated.append(validated.content());
                EvidenceRepository.RawEvidence rawProposal = evidence.read(new EvidenceRepository.ReadRequest(
                        request.taskId(), request.attemptId(), result.resultReference().uri(),
                        "workflow", "project-developer-patch"));
                if (!result.resultReference().digest().equals(rawProposal.digest())
                        || !"agent-result".equals(rawProposal.type())
                        || !"COMPLETE".equals(rawProposal.status())) {
                    throw new SecurityException("Developer proposal changed before projection");
                }
                var proposalArtifact = new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                        rawProposal.uri(), rawProposal.digest(), rawProposal.content().length,
                        rawProposal.status(), "ACCEPTED");
                String proposalId = proposal.path("proposal_id").asText();
                if (!proposalIds.add(proposalId)) {
                    throw new SecurityException("Developer patch proposal IDs must be unique");
                }
                reviewed.add(new ReviewedSpecialistResult(proposalId, "developer", proposalArtifact));
                state.recordArtifact(proposalId, "developer-patch-proposal", rawProposal.status(),
                        rawProposal.classification(), rawProposal.uri(), rawProposal.digest(),
                        rawProposal.content().length, true);
            }
            String combined = PatchIntegrator.normalize(consolidated.toString());
            byte[] content = combined.getBytes(StandardCharsets.UTF_8);
            String digest = PatchIntegrator.digestFor(combined);
            EvidenceRepository.StoredEvidence candidate = evidence.store(new EvidenceRepository.StoreRequest(
                    request.taskId(), request.attemptId(), "code-patch", "text/x-diff",
                    content, digest, "workflow"));
            if (!digest.equals(candidate.digest()) || !"COMPLETE".equals(candidate.status())) {
                throw new SecurityException("Consolidated Developer patch Evidence is incomplete");
            }
            state.patch = combined;
            state.recordArtifact("developer-patch-candidate", "code-patch", candidate.status(),
                    candidate.classification(), candidate.uri(), candidate.digest(), candidate.sizeBytes(), true);
            memory.project("hierarchical-developer-patches:" + digest, state);
            return new AcceptedDeveloperPatches(
                    new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                            candidate.uri(), candidate.digest(), candidate.sizeBytes(), candidate.status(), "GENERATED"),
                    reviewed);
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Cannot accept hierarchical Developer patches", failure));
        }
    }

    private DeveloperTask storeDeveloperTask(PrepareDeveloperTasks request, TaskState state,
                                             tools.jackson.databind.JsonNode task,
                                             tools.jackson.databind.JsonNode recommendedScope) throws Exception {
        return storeDeveloperTask(request.taskId(), request.attemptId(), request.repositoryId(),
                request.sourceCommit(), request.delegationPlanId(), request.architectureAssessmentId(),
                request.budget(), state, task.path("node_id").asText(), task.path("code_task_id").asText(),
                task, recommendedScope, "depends_on_task_ids", "success_criteria");
    }

    private DeveloperTask storeDeveloperTask(String taskId, String attemptId, String repositoryId,
                                             String sourceCommit, String delegationPlanId,
                                             String architectureAssessmentId, DelegationWorkflow.Budget taskBudget,
                                             TaskState state, String nodeId, String codeTaskId,
                                             tools.jackson.databind.JsonNode task,
                                             tools.jackson.databind.JsonNode recommendedScope,
                                             String dependenciesField, String criteriaField) throws Exception {
        List<String> writePaths = new java.util.ArrayList<>();
        recommendedScope.path("write_paths").forEach(path -> writePaths.add(path.asText()));
        if (writePaths.isEmpty()) throw new SecurityException("Developer write scope must not be empty");
        if (writePaths.stream().anyMatch(path -> path.equals(".git") || path.startsWith(".git/"))) {
            throw new SecurityException("Developer write scope targets repository metadata");
        }
        java.util.LinkedHashSet<String> modules = new java.util.LinkedHashSet<>();
        writePaths.forEach(path -> modules.add(path.contains("/") ? path.substring(0, path.indexOf('/')) : path));
        Instant issuedAt = Instant.now(clock);
        ObjectNode document = mapper.createObjectNode();
        document.put("schema_version", "1").put("code_task_id", codeTaskId)
                .put("task_id", taskId).put("attempt_id", attemptId)
                .put("delegation_plan_id", delegationPlanId).put("node_id", nodeId)
                .put("source_commit", sourceCommit)
                .put("worktree_id", worktreeId(taskId, attemptId, nodeId))
                .put("objective", task.path("objective").asText(state.request.requirement()))
                .put("risk_class", state.request.routingFacts().risk());
        if (architectureAssessmentId != null) {
            document.put("architecture_assessment_id", architectureAssessmentId);
        }
        ObjectNode scope = document.putObject("scope");
        scope.put("repository_id", repositoryId);
        ArrayNode moduleArray = scope.putArray("modules"); modules.forEach(moduleArray::add);
        java.util.LinkedHashSet<String> readPaths = new java.util.LinkedHashSet<>(modules);
        recommendedScope.path("read_paths").forEach(path -> readPaths.add(path.asText()));
        readPaths.addAll(writePaths);
        ArrayNode reads = scope.putArray("read_paths"); readPaths.forEach(reads::add);
        ArrayNode writes = scope.putArray("write_paths"); writePaths.forEach(writes::add);
        scope.putArray("forbidden_paths").add(".git");
        ArrayNode rules = scope.putArray("rules");
        writePaths.forEach(path -> rules.addObject().put("kind", "FILE").put("access", "WRITE").put("path", path));
        scope.put("max_changed_files", writePaths.size()).put("max_patch_bytes", 900_000);
        document.put("scope_digest", TemporalIds.sha256(mapper.writeValueAsString(scope)));
        ArrayNode dependencies = document.putArray("dependencies");
        task.path(dependenciesField).forEach(value -> dependencies.add(value.asText()));
        ArrayNode criteria = document.putArray("acceptance_criteria");
        task.path(criteriaField).forEach(value -> criteria.add(value.asText()));
        ObjectNode budget = document.putObject("budget");
        budget.put("max_turns", taskBudget.maxTurns()).put("max_tokens", taskBudget.maxTokens())
                .put("max_cost_micros", taskBudget.maxCostMicros())
                .put("timeout_seconds", taskBudget.timeoutSeconds());
        document.putArray("required_approval_ids");
        document.put("issued_at", issuedAt.toString())
                .put("deadline", issuedAt.plusSeconds(taskBudget.timeoutSeconds()).toString());
        java.util.LinkedHashSet<String> allowedReferences = new java.util.LinkedHashSet<>(
                Set.of(delegationPlanId, nodeId));
        if (architectureAssessmentId != null) allowedReferences.add(architectureAssessmentId);
        contracts.validate("code-task-v1", document, new MultiAgentContractValidator.ContractContext(
                taskId, attemptId, Set.copyOf(allowedReferences)));
        byte[] content = mapper.writeValueAsBytes(document);
        String digest = TemporalIds.sha256(new String(content, StandardCharsets.UTF_8));
        EvidenceRepository.StoredEvidence stored = evidence.store(new EvidenceRepository.StoreRequest(
                taskId, attemptId, "code-task", "application/json",
                content, digest, "workflow"));
        if (!digest.equals(stored.digest()) || !"COMPLETE".equals(stored.status())) {
            throw new SecurityException("Stored Developer task differs from its validated document");
        }
        A2aContracts.Part reference = A2aEvidencePartFactory.reference(
                codeTaskId, stored.uri(), stored.digest(), "code-task-v1", stored.sizeBytes());
        java.util.LinkedHashSet<String> dependsOn = new java.util.LinkedHashSet<>();
        task.path(dependenciesField).forEach(value -> dependsOn.add(value.asText()));
        return new DeveloperTask(nodeId, codeTaskId, Set.copyOf(dependsOn), taskBudget, reference);
    }

    private static DelegationWorkflow.Budget boundedBudget(
            tools.jackson.databind.JsonNode budget, DelegationWorkflow.Budget ceiling) {
        DelegationWorkflow.Budget requested = new DelegationWorkflow.Budget(
                budget.path("max_tokens").asLong(), budget.path("max_cost_micros").asLong(),
                budget.path("max_turns").asInt(), budget.path("timeout_seconds").asLong());
        if (requested.maxTokens() > ceiling.maxTokens()
                || requested.maxCostMicros() > ceiling.maxCostMicros()
                || requested.maxTurns() > ceiling.maxTurns()
                || requested.timeoutSeconds() > ceiling.timeoutSeconds()) {
            throw new SecurityException("Supervisor Developer budget exceeds the host ceiling");
        }
        return requested;
    }

    private tools.jackson.databind.JsonNode readAndValidate(
            String taskId, String attemptId, A2aActivities.EvidenceReference reference,
            String contract, Set<String> allowedReferenceIds, String purpose) throws Exception {
        if (reference == null || !contract.equals(reference.contract())
                || reference.uri() == null || !reference.uri().startsWith("evidence://")
                || reference.digest() == null || !reference.digest().matches("[0-9a-f]{64}")) {
            throw new SecurityException("Hierarchical Evidence reference is invalid");
        }
        EvidenceRepository.RawEvidence raw = evidence.read(new EvidenceRepository.ReadRequest(
                taskId, attemptId, reference.uri(), "workflow", purpose));
        if (!reference.digest().equals(raw.digest()) || !"agent-result".equals(raw.type())
                || !"COMPLETE".equals(raw.status())) {
            throw new SecurityException("Hierarchical Evidence changed after validation");
        }
        return contracts.validate(contract, mapper.readTree(raw.content()),
                new MultiAgentContractValidator.ContractContext(taskId, attemptId, allowedReferenceIds));
    }

    private tools.jackson.databind.JsonNode readCodeTask(
            String taskId, String attemptId, A2aActivities.EvidenceReference reference) throws Exception {
        if (reference == null || !"code-task-v1".equals(reference.contract())
                || reference.uri() == null || !reference.uri().startsWith("evidence://")
                || reference.digest() == null || !reference.digest().matches("[0-9a-f]{64}")) {
            throw new SecurityException("Developer code task reference is invalid");
        }
        EvidenceRepository.RawEvidence raw = evidence.read(new EvidenceRepository.ReadRequest(
                taskId, attemptId, reference.uri(), "workflow", "accept-developer-patch"));
        if (!reference.digest().equals(raw.digest()) || !"code-task".equals(raw.type())
                || !"COMPLETE".equals(raw.status())) {
            throw new SecurityException("Developer code task changed after materialization");
        }
        var document = mapper.readTree(raw.content());
        java.util.LinkedHashSet<String> allowedReferences = new java.util.LinkedHashSet<>(Set.of(
                document.path("delegation_plan_id").asText(), document.path("node_id").asText()));
        if (document.hasNonNull("architecture_assessment_id")) {
            allowedReferences.add(document.path("architecture_assessment_id").asText());
        }
        return contracts.validate("code-task-v1", document,
                new MultiAgentContractValidator.ContractContext(taskId, attemptId, Set.copyOf(allowedReferences)));
    }

    private static A2aActivities.EvidenceReference specialistReference(A2aContracts.Part part) {
        if (part == null || part.data() == null) throw new SecurityException("Developer task reference is missing");
        return new A2aActivities.EvidenceReference(String.valueOf(part.data().get("reference_id")),
                String.valueOf(part.data().get("uri")), String.valueOf(part.data().get("digest")),
                String.valueOf(part.data().get("contract")));
    }

    private static void requireDeveloperBinding(DeveloperTask task, tools.jackson.databind.JsonNode codeTask,
                                                tools.jackson.databind.JsonNode proposal, String sourceCommit) {
        for (String field : List.of("code_task_id", "node_id", "worktree_id", "scope_digest")) {
            if (!codeTask.path(field).asText().equals(proposal.path(field).asText())) {
                throw new SecurityException("Developer proposal changed its " + field);
            }
        }
        if (!task.codeTaskId().equals(proposal.path("code_task_id").asText())
                || !task.nodeId().equals(proposal.path("node_id").asText())
                || !sourceCommit.equals(proposal.path("source_commit").asText())) {
            throw new SecurityException("Developer proposal is outside its workflow identity");
        }
    }

    private static String worktreeId(String taskId, String attemptId, String nodeId) {
        return "worktree-" + nodeId + '-' + TemporalIds.sha256(
                String.join("\u0000", taskId, attemptId, nodeId)).substring(0, 16);
    }

    private static void requireAttemptAndCommit(TaskState state, String attemptId, String sourceCommit) {
        if (!attemptId.equals(state.workflowAttemptId) || !sourceCommit.equals(state.sourceCommit)) {
            throw new SecurityException("Hierarchical input is outside the projected workflow attempt");
        }
    }

    @Override
    public PreparedIndependentReview prepareIndependentReview(PrepareIndependentReview request) {
        requireValid(request);
        TaskState state = memory.find(request.taskId()).orElseThrow(
                () -> new IllegalArgumentException("Unknown hierarchical task"));
        if (!request.attemptId().equals(state.workflowAttemptId)
                || !request.sourceCommit().equals(state.sourceCommit)) {
            throw new SecurityException("Independent review input is outside the projected workflow attempt");
        }
        try {
            if (state.pendingEffect == null) {
                throw new SecurityException("Independent review requires a prepared external effect");
            }
            List<String> changedFiles = changedFiles(state.patch);
            List<IndependentReviewBundle.ResultReference> reviewedResults = request.reviewedResults().stream()
                    .map(result -> new IndependentReviewBundle.ResultReference(result.documentId(), result.role(),
                            result.artifact().uri(), result.artifact().digest()))
                    .toList();
            Map<String, EvidenceRepository.EvidenceReference> references = new LinkedHashMap<>();
            Map<String, String> digests = new LinkedHashMap<>();
            request.artifacts().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                var artifact = entry.getValue();
                references.put(entry.getKey(), new EvidenceRepository.EvidenceReference(
                        artifact.uri(), artifact.digest(), artifact.status()));
                digests.put(entry.getKey(), artifact.digest());
            });
            EvidenceRepository.PolicyDecision policy = new EvidenceRepository.PolicyDecision(
                    "1", request.taskId(), request.attemptId(), "hierarchical-gates", "1", "ALLOW",
                    List.of("pre-review-gates-passed"), Map.copyOf(digests), Instant.now(clock));
            EvidenceRepository.StoredManifest manifest = evidence.createManifest(
                    new EvidenceRepository.ManifestRequest(request.taskId(), request.attemptId(),
                            request.repositoryId(), request.sourceCommit(), request.artifacts().get("patch").digest(),
                            Map.copyOf(references), policy, "workflow"));
            if (!"COMPLETE".equals(manifest.status())) {
                throw new SecurityException("Hierarchical review manifest is incomplete");
            }
            var patch = request.artifacts().get("patch");
            IndependentReviewBundle bundle = new IndependentReviewBundle(
                    request.taskId(), request.attemptId(), request.sourceCommit(),
                    new IndependentReviewBundle.ConsolidatedPatch(
                            "integrated-patch", patch.uri(), patch.digest(), changedFiles),
                    new IndependentReviewBundle.FinalManifest(
                            manifest.manifestId(), manifest.uri(), manifest.digest()),
                    reviewedResults, List.of(), Map.copyOf(requiredArtifactDigests(request.artifacts())));
            bundle.requireProductionArtifactBinding(request.artifacts());
            state.bindApprovalManifest(manifest.manifestId(), manifest.uri(), manifest.digest());
            memory.project("hierarchical-review-manifest:" + manifest.digest(), state);
            return new PreparedIndependentReview(bundle, manifest);
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        }
    }

    private static Map<String, String> requiredArtifactDigests(
            Map<String, com.example.aifactory.service.PipelineStepContracts.ArtifactReference> artifacts) {
        Map<String, String> required = new LinkedHashMap<>();
        for (String name : List.of("plan", "patch", "tests", "quality", "security")) {
            var artifact = artifacts.get(name);
            if (artifact == null) throw new SecurityException("Hierarchical review is missing evidence: " + name);
            required.put(name, artifact.digest());
        }
        return required;
    }

    private static List<String> changedFiles(String patch) {
        if (patch == null) throw new SecurityException("Hierarchical review is missing the integrated patch");
        java.util.LinkedHashSet<String> paths = new java.util.LinkedHashSet<>();
        for (String line : patch.split("\\n")) {
            Matcher matcher = DIFF_HEADER.matcher(line);
            if (matcher.matches()) paths.add(matcher.group(2));
        }
        if (paths.isEmpty()) throw new SecurityException("Integrated patch contains no reviewable file");
        return List.copyOf(paths);
    }

    private static void requireValid(PrepareSpecialistTask request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.repositoryId() == null || !request.repositoryId().matches("[a-z0-9][a-z0-9-]{1,62}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.delegationPlanId() == null
                || !request.delegationPlanId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || request.nodeId() == null || !request.nodeId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || request.parentRole() == null || !request.parentRole().matches("[a-z][a-z0-9-]{1,63}")
                || !SPECIALIST_ROLES.contains(request.role()) || request.budget() == null
                || request.successCriteria().isEmpty() || request.readPaths().size() > 128
                || request.writePaths().size() > 64 || request.allowedTools().size() > 16
                || request.inputs().size() > 64) {
            throw new IllegalArgumentException("Hierarchical specialist task request is invalid");
        }
        request.inputs().forEach(input -> {
            if (input == null || !Set.of("TICKET", "CONTRACT", "SPECIALIST_RESULT", "EVIDENCE",
                    "POLICY_DECISION").contains(input.kind()) || input.uri() == null || input.uri().isBlank()
                    || input.digest() == null || !input.digest().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Hierarchical specialist input evidence is invalid");
            }
        });
    }

    private static void requireValid(AcceptSpecialistResult request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || !SPECIALIST_ROLES.contains(request.role()) || request.contract() == null
                || request.reference() == null || !request.role().equals(roleFor(request.contract()))
                || !request.contract().equals(request.reference().contract())
                || request.reference().uri() == null || !request.reference().uri().startsWith("evidence://")
                || request.reference().digest() == null
                || !request.reference().digest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Hierarchical specialist result request is invalid");
        }
    }

    private static void requireValid(PrepareIndependentReview request) {
        Set<String> requiredRoles = request == null ? Set.of() : request.requiredRoles();
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.repositoryId() == null || !request.repositoryId().matches("[a-z0-9][a-z0-9-]{1,62}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || !(FULL_REVIEW_ROLES.equals(requiredRoles) || SHORT_REVIEW_ROLES.equals(requiredRoles))
                || request.reviewedResults().size() < requiredRoles.size()
                || request.reviewedResults().size() > 9
                || request.reviewedResults().stream().anyMatch(java.util.Objects::isNull)
                || !request.reviewedResults().stream().map(ReviewedSpecialistResult::role)
                .collect(java.util.stream.Collectors.toSet()).containsAll(requiredRoles)
                || request.reviewedResults().stream().anyMatch(result -> result.documentId() == null
                || !result.documentId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || !requiredRoles.contains(result.role()) || result.artifact() == null)) {
            throw new IllegalArgumentException("Hierarchical independent review request is invalid");
        }
        requiredArtifactDigests(request.artifacts());
    }

    private static void requireValid(PrepareDeveloperTasks request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.repositoryId() == null || !request.repositoryId().matches("[a-z0-9][a-z0-9-]{1,62}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.delegationPlanId() == null
                || !request.delegationPlanId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || request.architectureAssessmentId() == null
                || !request.architectureAssessmentId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || request.architectureReference() == null || request.integrationReference() == null
                || request.budget() == null) {
            throw new IllegalArgumentException("Hierarchical Developer task preparation is invalid");
        }
    }

    private static void requireValid(PrepareShortDeveloperTasks request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.repositoryId() == null || !request.repositoryId().matches("[a-z0-9][a-z0-9-]{1,62}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.delegationPlanId() == null
                || !request.delegationPlanId().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || request.planReference() == null || request.budget() == null) {
            throw new IllegalArgumentException("Short-path Developer task preparation is invalid");
        }
    }

    private static void requireValid(AcceptDeveloperPatches request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.results().isEmpty() || request.results().size() > 4
                || request.results().stream().anyMatch(result -> result == null || result.task() == null
                || result.resultReference() == null)
                || request.results().stream().map(result -> result.task().nodeId()).distinct().count()
                != request.results().size()) {
            throw new IllegalArgumentException("Hierarchical Developer patch acceptance is invalid");
        }
    }

    private static String roleFor(String contract) {
        return switch (contract) {
            case "delegation-plan-v1" -> "supervisor";
            case "architecture-assessment-v1" -> "architecture-agent";
            case "integration-proposal-v1" -> "code-agent";
            case "test-strategy-v1" -> "test-design";
            case "test-assessment-v1" -> "test-agent";
            case "security-assessment-v1" -> "security-agent";
            default -> "";
        };
    }
}
