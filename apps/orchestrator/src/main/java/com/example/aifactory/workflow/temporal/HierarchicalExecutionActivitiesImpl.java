package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aEvidencePartFactory;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.service.MultiAgentContractValidator;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.TaskMemory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Evidence-backed implementation of the hierarchical specialist input boundary. */
@Component
public final class HierarchicalExecutionActivitiesImpl implements HierarchicalExecutionActivities {
    private static final Set<String> SPECIALIST_ROLES = Set.of(
            "architecture-agent", "code-agent", "test-design", "test-agent", "security-agent");
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
                        "Code Agent integration plan accepted");
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

    private static String roleFor(String contract) {
        return switch (contract) {
            case "architecture-assessment-v1" -> "architecture-agent";
            case "integration-proposal-v1" -> "code-agent";
            case "test-strategy-v1" -> "test-design";
            case "test-assessment-v1" -> "test-agent";
            case "security-assessment-v1" -> "security-agent";
            default -> "";
        };
    }
}
