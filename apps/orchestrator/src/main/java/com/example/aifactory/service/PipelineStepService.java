package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.EvidenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Business steps callable by either the local parity oracle or Temporal activities. */
@Service
public class PipelineStepService {
    private static final Pattern PATCH_FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE);
    private static final int MAX_PATCH_REPAIR_ATTEMPTS = 2;
    private static final Logger log = LoggerFactory.getLogger(PipelineStepService.class);

    private final AiFactoryProperties props;
    private final ProcessRunner runner;
    private final RepositoryContextProvider contextService;
    private final AgentResponseValidator agentResponses;
    private final SandboxExecutor sandbox;
    private final PatchIntegrator patchIntegrator;
    private final AssuranceGateway assurance;
    private final ScmDeliveryGateway scmDelivery;
    private final ObjectMapper objectMapper;
    private final EvidenceRepository evidence;

    public PipelineStepService(AiFactoryProperties props, ProcessRunner runner,
                               RepositoryContextProvider contextService, AgentResponseValidator agentResponses,
                               SandboxExecutor sandbox, PatchIntegrator patchIntegrator,
                               AssuranceGateway assurance, ScmDeliveryGateway scmDelivery,
                               ObjectMapper objectMapper, EvidenceRepository evidence) {
        this.props = props;
        this.runner = runner;
        this.contextService = contextService;
        this.agentResponses = agentResponses;
        this.sandbox = sandbox;
        this.patchIntegrator = patchIntegrator;
        this.assurance = assurance;
        this.scmDelivery = scmDelivery;
        this.objectMapper = objectMapper;
        this.evidence = evidence;
    }

    public PipelineProjectionEvent.WorkspaceInitialized initializeWorkspace(TaskState state) throws Exception {
        Path root = Path.of(props.workspaceRoot());
        Files.createDirectories(root);
        Path workspace = root.resolve(state.id).toAbsolutePath();
        Files.createDirectories(workspace);
        log.info("Task {} ({}) workspace initialized", state.id, state.ticketNumber);
        return new PipelineProjectionEvent.WorkspaceInitialized(workspace.toString());
    }

    public PipelineProjectionEvent.StepExecution cloneSource(TaskState state, Path workspace,
                                                             PipelineStepContracts.Command command) throws Exception {
        command.requireStep("clone");
        runner.run(List.of("git", "clone", "--depth", "1", "--branch", state.request.effectiveBranch(),
                state.request.repositoryUrl(), workspace.toString()), null, Duration.ofMinutes(2));
        String sourceCommit = runner.run(List.of("git", "rev-parse", "HEAD"), workspace,
                Duration.ofSeconds(10)).strip();
        String model = "a2a-agent-runtime";
        log.info("Task {} ({}) cloned source commit {} using model {}", state.id, state.ticketNumber,
                sourceCommit, model);
        var result = PipelineStepContracts.Result.from(command, sourceCommit, Map.of());
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.SourceCloned(sourceCommit, model));
    }

    public PreparedAgentInput prepareAgentInput(TaskState state, Path workspace, String operation,
                                                PipelineStepContracts.Command command,
                                                PipelineStepContracts.ArtifactReference validationError,
                                                int repairAttempt) throws Exception {
        if ("ASSESS_TESTS".equals(operation)) {
            PreparedTests tests = prepareTests(state, workspace, command);
            return new PreparedAgentInput(untrusted("REQUIREMENT", state.request.requirement())
                    + untrusted("PATCH", state.patch)
                    + untrusted("DETERMINISTIC_TEST_EVIDENCE", tests.summary()), tests.artifact());
        }
        String payload = switch (operation) {
            case "PLAN" -> untrusted("REQUIREMENT", state.request.requirement())
                    + untrusted("REPOSITORY_CONTEXT", contextService.collectForRole(
                    workspace, state.id, state.sourceCommit, "planner"));
            case "GENERATE_PATCH" -> untrusted("REQUIREMENT", state.request.requirement())
                    + untrusted("PLAN", state.plan)
                    + untrusted("REPOSITORY_CONTEXT", contextService.collectForRole(
                    workspace, state.id, state.sourceCommit, "developer"));
            case "REPAIR_PATCH" -> repairPayload(state, workspace, command, validationError, repairAttempt);
            case "REVIEW" -> untrusted("REQUIREMENT", state.request.requirement())
                    + untrusted("PLAN", state.plan) + untrusted("PATCH", state.patch)
                    + untrusted("ASSURANCE_RESULTS", objectMapper.writeValueAsString(state.assuranceResults));
            default -> throw new IllegalArgumentException("Unsupported pipeline A2A operation: " + operation);
        };
        return new PreparedAgentInput(payload, null);
    }

    public PipelineProjectionEvent.StepExecution consumeAgentResult(TaskState state, Path workspace,
                                                                     PipelineStepContracts.Command command,
                                                                     String operation, String content,
                                                                     PipelineProjectionEvent.AgentMetadata metadata,
                                                                     PipelineStepContracts.ArtifactReference supportingArtifact)
            throws Exception {
        return switch (operation) {
            case "PLAN" -> consumePlan(state, workspace, command, content, metadata);
            case "GENERATE_PATCH" -> consumePatch(state, command, content, metadata, 0, "GENERATED");
            case "REPAIR_PATCH" -> consumePatch(state, command, content, metadata, 1, "REPAIRED");
            case "ASSESS_TESTS" -> consumeTests(state, workspace, command, content, metadata, supportingArtifact);
            case "REVIEW" -> consumeReview(state, workspace, command, content, metadata);
            default -> throw new IllegalArgumentException("Unsupported pipeline A2A operation: " + operation);
        };
    }

    public PatchValidationOutcome validatePatchCandidate(TaskState state, Path workspace,
                                                          PipelineStepContracts.Command command) {
        command.requireStep("validate-patch-candidate");
        try {
            String patch = patchIntegrator.validate(workspace, state.id, state.sourceCommit, state.patch).content();
            var artifact = persist(command, "patch", "text/x-diff", patch, "VALID");
            var execution = PipelineProjectionEvent.StepExecution.of(
                    PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("patch", artifact)),
                    new PipelineProjectionEvent.PatchProduced(patch, 0, PipelineProjectionEvent.AgentMetadata.none()));
            return new PatchValidationOutcome(true, execution, null);
        } catch (Exception invalid) {
            String error = invalid.getMessage() == null ? invalid.getClass().getSimpleName() : invalid.getMessage();
            var artifact = persist(command, "patch-validation-error", "text/plain",
                    tail(error, 4_000), "INVALID");
            return new PatchValidationOutcome(false,
                    new PipelineProjectionEvent.StepExecution(PipelineStepContracts.Result.from(
                            command, state.sourceCommit, Map.of("patch-validation-error", artifact)), List.of()),
                    artifact);
        }
    }

    private String repairPayload(TaskState state, Path workspace, PipelineStepContracts.Command command,
                                 PipelineStepContracts.ArtifactReference error, int repairAttempt) throws Exception {
        if (repairAttempt < 1 || repairAttempt > MAX_PATCH_REPAIR_ATTEMPTS || error == null) {
            throw new IllegalArgumentException("Patch repair attempt is invalid");
        }
        EvidenceRepository.RawEvidence failure = evidence.read(new EvidenceRepository.ReadRequest(
                command.taskId(), command.attemptId(), error.uri(), "workflow", "repair-patch"));
        String context = contextService.collectForRole(workspace, state.id, state.sourceCommit, "patch-repair");
        return untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", context)
                + untrusted("CURRENT_FILE_CONTENTS", safeAffectedFileContext(workspace, state.patch))
                + untrusted("INVALID_PATCH", state.patch)
                + untrusted("GIT_APPLY_ERROR", new String(failure.content(), StandardCharsets.UTF_8))
                + untrusted("REPAIR_ATTEMPT", Integer.toString(repairAttempt));
    }

    private static String safeAffectedFileContext(Path workspace, String patch) {
        try {
            return affectedFileContext(workspace, patch);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot collect patch repair context", failure);
        }
    }

    public record PatchValidationOutcome(boolean valid, PipelineProjectionEvent.StepExecution execution,
                                         PipelineStepContracts.ArtifactReference error) {}

    public PipelineProjectionEvent.StepExecution applyPatch(TaskState state, Path workspace,
                                                            PipelineStepContracts.Command command) throws Exception {
        command.requireStep("apply-patch");
        patchIntegrator.apply(workspace, state.id, state.sourceCommit,
                new PatchIntegrator.IntegratedPatch(state.patch, PatchIntegrator.digestFor(state.patch)));
        return new PipelineProjectionEvent.StepExecution(
                PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of()), List.of());
    }

    private PreparedTests prepareTests(TaskState state, Path workspace,
                                       PipelineStepContracts.Command command) throws Exception {
        String deterministicTests = tail(sandbox.test(workspace, state.id, state.sourceCommit), 12_000);
        var artifact = persist(command, "tests-deterministic", "text/plain", deterministicTests, "PASSED");
        return new PreparedTests(deterministicTests, artifact);
    }

    private PipelineProjectionEvent.StepExecution consumePlan(TaskState state, Path workspace,
                                                               PipelineStepContracts.Command command,
                                                               String content,
                                                               PipelineProjectionEvent.AgentMetadata metadata)
            throws Exception {
        agentResponses.requireImplementablePlan(content);
        Files.writeString(workspace.resolve(".ai-plan.md"), content);
        var artifact = persist(command, "plan", "text/markdown", content, "IMPLEMENTABLE");
        return PipelineProjectionEvent.StepExecution.of(
                PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("plan", artifact)),
                new PipelineProjectionEvent.PlanProduced(content, metadata));
    }

    private PipelineProjectionEvent.StepExecution consumePatch(TaskState state,
                                                                PipelineStepContracts.Command command,
                                                                String content,
                                                                PipelineProjectionEvent.AgentMetadata metadata,
                                                                int repairs, String verdict) {
        String patch = PatchIntegrator.normalize(content);
        var artifact = persist(command, "patch-candidate", "text/x-diff", patch, verdict);
        return PipelineProjectionEvent.StepExecution.of(
                PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("patch-candidate", artifact)),
                new PipelineProjectionEvent.PatchProduced(patch, repairs, metadata));
    }

    private PipelineProjectionEvent.StepExecution consumeTests(TaskState state, Path workspace,
                                                                PipelineStepContracts.Command command,
                                                                String content,
                                                                PipelineProjectionEvent.AgentMetadata metadata,
                                                                PipelineStepContracts.ArtifactReference supportingArtifact)
            throws Exception {
        agentResponses.requireTesterReport(content);
        if (supportingArtifact == null || !"PASSED".equals(supportingArtifact.verdict())) {
            throw new SecurityException("A2A test assessment lacks deterministic test evidence");
        }
        EvidenceRepository.RawEvidence deterministic = evidence.read(new EvidenceRepository.ReadRequest(
                command.taskId(), command.attemptId(), supportingArtifact.uri(),
                "workflow", "pipeline-test-consolidation"));
        if (!supportingArtifact.digest().equals(deterministic.digest())) {
            throw new SecurityException("Deterministic test evidence digest changed");
        }
        String summary = new String(deterministic.content(), StandardCharsets.UTF_8)
                + "\n\n--- AI TESTER REVIEW ---\n" + content;
        Files.createDirectories(workspace.resolve(".ai-factory"));
        Files.writeString(workspace.resolve(".ai-factory/test.txt"), summary);
        var artifact = persist(command, "tests", "text/plain", summary, "PASSED");
        var assuranceResult = evidenceResult(command, artifact);
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("tests", artifact));
        return PipelineProjectionEvent.StepExecution.of(result, new PipelineProjectionEvent.TestsCompleted(
                summary, Map.of("tests", assuranceResult), metadata));
    }

    public PipelineProjectionEvent.StepExecution quality(TaskState state, Path workspace,
                                                         PipelineStepContracts.Command command) throws Exception {
        command.requireStep("quality");
        String summary = tail(sandbox.quality(workspace, state.id, state.sourceCommit), 12_000);
        JsonNode assuranceResult = assurance.requireQualityGate(state.id, state.sourceCommit, summary);
        Map<String, Object> projection = objectMapper.convertValue(assuranceResult, Map.class);
        var artifact = persist(command, "quality", "text/plain", summary, "PASSED");
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("quality", artifact));
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.QualityCompleted(summary, Map.of("quality", projection)));
    }

    public PipelineProjectionEvent.StepExecution security(TaskState state, Path workspace,
                                                          PipelineStepContracts.Command command) throws Exception {
        command.requireStep("security");
        String summary = tail(sandbox.security(workspace, state.id, state.sourceCommit), 12_000);
        var securityArtifact = persist(command, "security", "text/plain", summary, "PASSED");
        Map<String, Object> securityResult = evidenceResult(command, securityArtifact);
        Path sbom = workspace.resolve(".ai-factory/sbom.cdx.json");
        byte[] sbomContent = Files.readAllBytes(sbom);
        var sbomArtifact = persist(command, "sbom", "application/vnd.cyclonedx+json", sbomContent, "COMPLETE");
        Map<String, Object> sbomResult = Map.of("schema_version", "1", "task_id", state.id,
                "attempt_id", "pipeline-1", "source_commit", state.sourceCommit, "format", "CYCLONEDX_JSON",
                "uri", sbomArtifact.uri(), "digest", sbomArtifact.digest(),
                "status", "COMPLETE");
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit,
                Map.of("security", securityArtifact, "sbom", sbomArtifact));
        return PipelineProjectionEvent.StepExecution.of(result, new PipelineProjectionEvent.SecurityCompleted(
                summary, Map.of("security", securityResult, "sbom", sbomResult)));
    }

    private PipelineProjectionEvent.StepExecution consumeReview(TaskState state, Path workspace,
                                                                 PipelineStepContracts.Command command,
                                                                 String content,
                                                                 PipelineProjectionEvent.AgentMetadata metadata)
            throws Exception {
        AgentResponseValidator.ReviewSummary summary = agentResponses.summarizeReview(content);
        logReviewerDecision(state, summary);
        agentResponses.requireReviewAllowsApproval(summary);
        Files.writeString(workspace.resolve(".ai-review.md"), content);
        var artifact = persist(command, "review", "application/json", content, summary.decision());
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("review", artifact));
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.ReviewCompleted(content, metadata));
    }

    public PipelineProjectionEvent.DeliveryPrepared prepareDelivery(TaskState state) {
        PendingEffect pendingEffect = new PendingEffect("scm.create_draft_pull_request",
                Map.of("base_branch", state.request.effectiveBranch(),
                        "repository", safeRepositoryLabel(state.request.repositoryUrl()),
                        "title", "[" + state.ticketNumber + "] " + conciseRequirement(state.request.requirement())),
                "Créera une branche distante, un commit et une pull request brouillon dans le dépôt indiqué.",
                "ALLOW", true);
        return new PipelineProjectionEvent.DeliveryPrepared(pendingEffect);
    }

    public PipelineProjectionEvent.StepExecution deliver(TaskState state,
                                                         PipelineStepContracts.Command command) throws Exception {
        command.requireStep("delivery");
        Path workspace = Path.of(state.workspace);
        String pullRequestUrl = scmDelivery.createDraftPullRequest(workspace, state.request.repositoryUrl(),
                state.request.effectiveBranch(), state.id, command.attemptId(), state.sourceCommit,
                state.request.requirement());
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of());
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.PullRequestCreated(pullRequestUrl));
    }

    public void writeRunMetadata(Path workspace, TaskState state) throws Exception {
        writeMetadata(workspace, state);
    }

    public record PreparedAgentInput(String payload,
                                     PipelineStepContracts.ArtifactReference supportingArtifact) {}

    private record PreparedTests(String summary, PipelineStepContracts.ArtifactReference artifact) {}

    static String stripFence(String value) {
        return PatchIntegrator.stripFence(value);
    }

    static String untrusted(String label, String content) {
        if (label == null || !label.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid untrusted-data label");
        }
        String escaped = (content == null ? "" : content).replace("</" + label + ">", "&lt;/" + label + "&gt;");
        return "\n<" + label + " trust=\"untrusted\">\n" + escaped + "\n</" + label + ">\n";
    }

    private static String affectedFileContext(Path workspace, String patch) throws Exception {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        Matcher matcher = PATCH_FILE.matcher(patch);
        while (matcher.find()) files.add(matcher.group(1));
        StringBuilder context = new StringBuilder();
        for (String file : files) {
            Path path = workspace.resolve(file).normalize();
            if (!path.startsWith(workspace) || !Files.isRegularFile(path)) continue;
            String content = Files.readString(path);
            if (content.length() > 12_000) content = content.substring(0, 12_000) + "\n...[truncated]";
            context.append("\n--- FILE: ").append(file).append(" ---\n").append(content).append('\n');
            if (context.length() >= 40_000) break;
        }
        return context.isEmpty() ? "No patched files could be read from the workspace." : context.toString();
    }

    private static void logReviewerDecision(TaskState state, AgentResponseValidator.ReviewSummary review) {
        if (review.findings().isEmpty()) {
            log.info("Task {} ({}) reviewer decision={}; no findings reported", state.id, state.ticketNumber,
                    review.decision());
            return;
        }
        log.warn("Task {} ({}) reviewer decision={}; findings: {}", state.id, state.ticketNumber,
                review.decision(), review.findingCounts());
        for (int index = 0; index < review.findings().size(); index++) {
            AgentResponseValidator.ReviewFinding finding = review.findings().get(index);
            log.warn("Task {} ({}) reviewer finding {}/{}: severity={}, file={}, rule={}, recommended_fix={}",
                    state.id, state.ticketNumber, index + 1, review.findings().size(), logField(finding.severity()),
                    logField(finding.file()), logField(finding.rule()), logField(finding.fix()));
        }
    }

    private static String logField(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 397) + "...";
    }

    private static String tail(String value, int max) {
        return value.length() <= max ? value : "...[truncated]...\n" + value.substring(value.length() - max);
    }

    private PipelineStepContracts.ArtifactReference persist(PipelineStepContracts.Command command, String type,
                                                            String mediaType, String content, String verdict) {
        return persist(command, type, mediaType, content.getBytes(StandardCharsets.UTF_8), verdict);
    }

    private PipelineStepContracts.ArtifactReference persist(PipelineStepContracts.Command command, String type,
                                                            String mediaType, byte[] content, String verdict) {
        String digest = sha256(content);
        EvidenceRepository.StoredEvidence stored = evidence.store(new EvidenceRepository.StoreRequest(
                command.taskId(), command.attemptId(), type, mediaType, content, digest, "workflow"));
        return new PipelineStepContracts.ArtifactReference(stored.uri(), stored.digest(), stored.sizeBytes(),
                stored.status(), verdict);
    }

    private static Map<String, Object> evidenceResult(PipelineStepContracts.Command command,
                                                      PipelineStepContracts.ArtifactReference artifact) {
        return Map.of("schema_version", "1", "task_id", command.taskId(),
                "attempt_id", command.attemptId(), "source_commit", command.sourceCommit(),
                "type", command.step(), "verdict", artifact.verdict(),
                "evidence", Map.of("uri", artifact.uri(), "digest", artifact.digest(),
                        "size_bytes", artifact.sizeBytes(), "status", artifact.status()));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot digest assurance result", exception);
        }
    }

    private static String safeRepositoryLabel(String repositoryUrl) {
        try {
            java.net.URI uri = java.net.URI.create(repositoryUrl);
            return (uri.getHost() == null ? "repository" : uri.getHost()) + uri.getPath();
        } catch (IllegalArgumentException ignored) {
            return "repository";
        }
    }

    private static String conciseRequirement(String requirement) {
        String normalized = requirement.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 77) + "...";
    }

    private static void writeMetadata(Path workspace, TaskState state) throws Exception {
        Files.createDirectories(workspace.resolve(".ai-factory"));
        String fingerprints = state.promptFingerprints.entrySet().stream()
                .map(entry -> "    \"" + entry.getKey() + "\": \"" + entry.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(",\n"));
        String metadata = "{\n" + "  \"ticket_number\": \"" + state.ticketNumber + "\",\n"
                + "  \"source_commit\": \"" + state.sourceCommit + "\",\n"
                + "  \"model\": \"" + state.model + "\",\n"
                + "  \"prompts\": {\n" + fingerprints + "\n  }\n}\n";
        Files.writeString(workspace.resolve(".ai-factory/run-metadata.json"), metadata);
    }
}
