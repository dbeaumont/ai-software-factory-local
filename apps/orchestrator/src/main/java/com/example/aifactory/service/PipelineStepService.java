package com.example.aifactory.service;

import com.example.aifactory.config.AgentToolingProperties;
import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.EvidenceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.function.Predicate;
import java.util.function.Supplier;
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
    private final PromptService prompts;
    private final LlmGatewayClient llm;
    private final AgentResponseValidator agentResponses;
    private final SandboxExecutor sandbox;
    private final PatchIntegrator patchIntegrator;
    private final AssuranceGateway assurance;
    private final ScmDeliveryGateway scmDelivery;
    private final ObjectMapper objectMapper;
    private final AgentToolingProperties agentTooling;
    private final AgentContextToolHost agentTools;
    private final EvidenceRepository evidence;
    private final WorkflowOperationalMetrics operationalMetrics;
    private final Counter plannerContractRetries;

    public PipelineStepService(AiFactoryProperties props, ProcessRunner runner,
                               RepositoryContextProvider contextService, PromptService prompts,
                               LlmGatewayClient llm, AgentResponseValidator agentResponses,
                               SandboxExecutor sandbox, PatchIntegrator patchIntegrator,
                               AssuranceGateway assurance, ScmDeliveryGateway scmDelivery,
                               MeterRegistry metrics, ObjectMapper objectMapper,
                               AgentToolingProperties agentTooling, AgentContextToolHost agentTools,
                               EvidenceRepository evidence) {
        this.props = props;
        this.runner = runner;
        this.contextService = contextService;
        this.prompts = prompts;
        this.llm = llm;
        this.agentResponses = agentResponses;
        this.sandbox = sandbox;
        this.patchIntegrator = patchIntegrator;
        this.assurance = assurance;
        this.scmDelivery = scmDelivery;
        this.objectMapper = objectMapper;
        this.agentTooling = agentTooling;
        this.agentTools = agentTools;
        this.evidence = evidence;
        this.operationalMetrics = new WorkflowOperationalMetrics(metrics);
        this.plannerContractRetries = Counter.builder("ai_factory_planner_contract_retries")
                .description("Planner calls retried once after an invalid response contract")
                .register(metrics);
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
        String model = llm.modelName();
        log.info("Task {} ({}) cloned source commit {} using model {}", state.id, state.ticketNumber,
                sourceCommit, model);
        var result = PipelineStepContracts.Result.from(command, sourceCommit, Map.of());
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.SourceCloned(sourceCommit, model));
    }

    public PipelineProjectionEvent.StepExecution plan(TaskState state, Path workspace,
                                                      PipelineStepContracts.Command command) throws Exception {
        command.requireStep("plan");
        String plannerContext = agentTooling.enabledFor("planner")
                ? "Use the authorized context tools to retrieve only the repository evidence needed for this plan."
                : contextService.collectForRole(workspace, state.id, state.sourceCommit, "planner");
        AgentOutput output = chat(state, "planner", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("REPOSITORY_CONTEXT", plannerContext));
        agentResponses.requireImplementablePlan(output.content());
        Files.writeString(workspace.resolve(".ai-plan.md"), output.content());
        var artifact = persist(command, "plan", "text/markdown", output.content(), "IMPLEMENTABLE");
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("plan", artifact));
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.PlanProduced(output.content(), output.metadata()));
    }

    public PipelineProjectionEvent.StepExecution generateAndRepairPatch(TaskState state, Path workspace,
                                                                        PipelineStepContracts.Command command) throws Exception {
        command.requireStep("generate-patch");
        String developerContext = contextService.collectForRole(workspace, state.id, state.sourceCommit, "developer");
        AgentOutput generated = chat(state, "developer", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", developerContext));
        PatchRepairResult repaired = validateAndRepairPatch(state, workspace, generated.content());
        var artifact = persist(command, "patch", "text/x-diff", repaired.patch(), "VALID");
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("patch", artifact));
        return PipelineProjectionEvent.StepExecution.of(result, new PipelineProjectionEvent.PatchProduced(
                repaired.patch(), repaired.repairs(), generated.metadata().plus(repaired.agentMetadata())));
    }

    public PipelineProjectionEvent.StepExecution generatePatchCandidate(TaskState state, Path workspace,
                                                                         PipelineStepContracts.Command command)
            throws Exception {
        command.requireStep("generate-patch-candidate");
        String developerContext = contextService.collectForRole(workspace, state.id, state.sourceCommit, "developer");
        AgentOutput generated = chat(state, "developer", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", developerContext));
        String patch = PatchIntegrator.normalize(generated.content());
        var artifact = persist(command, "patch-candidate", "text/x-diff", patch, "GENERATED");
        return PipelineProjectionEvent.StepExecution.of(
                PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("patch-candidate", artifact)),
                new PipelineProjectionEvent.PatchProduced(patch, 0, generated.metadata()));
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

    public PipelineProjectionEvent.StepExecution repairPatchCandidate(TaskState state, Path workspace,
                                                                       PipelineStepContracts.Command command,
                                                                       PipelineStepContracts.ArtifactReference error,
                                                                       int repairAttempt) throws Exception {
        command.requireStep("repair-patch-candidate");
        if (repairAttempt < 1 || repairAttempt > MAX_PATCH_REPAIR_ATTEMPTS || error == null) {
            throw new IllegalArgumentException("Patch repair attempt is invalid");
        }
        EvidenceRepository.RawEvidence failure = evidence.read(new EvidenceRepository.ReadRequest(
                command.taskId(), command.attemptId(), error.uri(), "workflow", "repair-patch"));
        String context = contextService.collectForRole(workspace, state.id, state.sourceCommit, "patch-repair");
        AgentOutput repaired = chat(state, "patch-repair", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", context)
                + untrusted("CURRENT_FILE_CONTENTS", safeAffectedFileContext(workspace, state.patch))
                + untrusted("INVALID_PATCH", state.patch)
                + untrusted("GIT_APPLY_ERROR", new String(failure.content(), StandardCharsets.UTF_8))
                + untrusted("REPAIR_ATTEMPT", Integer.toString(repairAttempt)));
        String patch = PatchIntegrator.normalize(repaired.content());
        var artifact = persist(command, "patch-candidate", "text/x-diff", patch, "REPAIRED");
        return PipelineProjectionEvent.StepExecution.of(
                PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("patch-candidate", artifact)),
                new PipelineProjectionEvent.PatchProduced(patch, 1, repaired.metadata()));
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

    public PipelineProjectionEvent.StepExecution test(TaskState state, Path workspace,
                                                      PipelineStepContracts.Command command) throws Exception {
        command.requireStep("test");
        String deterministicTests = tail(sandbox.test(workspace, state.id, state.sourceCommit), 12_000);
        AgentOutput output = chat(state, "tester", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PATCH", state.patch)
                + untrusted("DETERMINISTIC_TEST_EVIDENCE", deterministicTests));
        agentResponses.requireTesterReport(output.content());
        String summary = deterministicTests + "\n\n--- AI TESTER REVIEW ---\n" + output.content();
        Files.createDirectories(workspace.resolve(".ai-factory"));
        Files.writeString(workspace.resolve(".ai-factory/test.txt"), summary);
        var artifact = persist(command, "tests", "text/plain", summary, "PASSED");
        var assuranceResult = evidenceResult(command, artifact);
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("tests", artifact));
        return PipelineProjectionEvent.StepExecution.of(result, new PipelineProjectionEvent.TestsCompleted(
                summary, Map.of("tests", assuranceResult), output.metadata()));
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

    public PipelineProjectionEvent.StepExecution review(TaskState state, Path workspace,
                                                        PipelineStepContracts.Command command) throws Exception {
        command.requireStep("review");
        AgentOutput output = chat(state, "reviewer", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PLAN", state.plan) + untrusted("PATCH", state.patch)
                + untrusted("ASSURANCE_RESULTS", objectMapper.writeValueAsString(state.assuranceResults)));
        AgentResponseValidator.ReviewSummary summary = agentResponses.summarizeReview(output.content());
        logReviewerDecision(state, summary);
        agentResponses.requireReviewAllowsApproval(summary);
        Files.writeString(workspace.resolve(".ai-review.md"), output.content());
        var artifact = persist(command, "review", "application/json", output.content(), summary.decision());
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("review", artifact));
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.ReviewCompleted(output.content(), output.metadata()));
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
                state.request.effectiveBranch(), state.id, state.sourceCommit, state.request.requirement());
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of());
        return PipelineProjectionEvent.StepExecution.of(result,
                new PipelineProjectionEvent.PullRequestCreated(pullRequestUrl));
    }

    private PatchRepairResult validateAndRepairPatch(TaskState state, Path workspace, String rawPatch) throws Exception {
        String patch = PatchIntegrator.normalize(rawPatch);
        int repairs = 0;
        PipelineProjectionEvent.AgentMetadata metadata = PipelineProjectionEvent.AgentMetadata.none();
        for (int repairAttempt = 0; repairAttempt <= MAX_PATCH_REPAIR_ATTEMPTS; repairAttempt++) {
            try {
                return new PatchRepairResult(
                        patchIntegrator.validate(workspace, state.id, state.sourceCommit, patch).content(),
                        repairs, metadata);
            } catch (Exception validationFailure) {
                if (repairAttempt == MAX_PATCH_REPAIR_ATTEMPTS) throw validationFailure;
                repairs++;
                operationalMetrics.repair();
                log.warn("Task {} ({}) patch validation failed; starting repair attempt {}/{}: {}",
                        state.id, state.ticketNumber, repairAttempt + 1, MAX_PATCH_REPAIR_ATTEMPTS,
                        validationFailure.getMessage());
                Files.writeString(workspace.resolve("changes.invalid.patch"), patch);
                String context = contextService.collectForRole(workspace, state.id, state.sourceCommit,
                        "patch-repair");
                AgentOutput repaired = chat(state, "patch-repair", untrusted("REQUIREMENT", state.request.requirement())
                        + untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", context)
                        + untrusted("CURRENT_FILE_CONTENTS", affectedFileContext(workspace, patch))
                        + untrusted("INVALID_PATCH", patch)
                        + untrusted("GIT_APPLY_ERROR", validationFailure.getMessage())
                        + untrusted("REPAIR_ATTEMPT", Integer.toString(repairAttempt + 1)));
                metadata = metadata.plus(repaired.metadata());
                patch = PatchIntegrator.normalize(repaired.content());
            }
        }
        throw new IllegalStateException("Patch validation exited unexpectedly");
    }

    private AgentOutput chat(TaskState state, String promptName, String untrustedInput) {
        String fingerprint = prompts.fingerprint(promptName);
        log.info("Task {} ({}) invoking {} agent with prompt sha256={}", state.id, state.ticketNumber,
                promptName, fingerprint.substring(0, 12));
        String systemPrompt = prompts.load(promptName);
        if (agentTooling.enabledFor(promptName)) {
            long started = System.nanoTime();
            AgentToolLoop loop = new AgentToolLoop(
                    messages -> llm.nextToolTurn(messages, agentTools.definitions(), maxTokensFor(promptName)),
                    agentTools.executor(state.id, "pipeline-1", state.sourceCommit, promptName),
                    agentTools.authorization());
            AgentToolLoop.Result result = loop.run(new AgentToolLoop.Actor(state.id, promptName), systemPrompt,
                    untrustedInput, new AgentToolLoop.Budget(6, Duration.ofMinutes(3), 12_000, 5_000_000));
            log.info("Task {} ({}) {} tool loop completed; turns={} tokens={} duration_ms={}",
                    state.id, state.ticketNumber, promptName, result.turns(), result.tokens(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            return new AgentOutput(result.finalResult(), new PipelineProjectionEvent.AgentMetadata(
                    Map.of(promptName, fingerprint), result.tokens(), result.costMicros(), result.turns()));
        }
        Map<String, Object> responseFormat = PlannerResponseFormat.forPrompt(promptName);
        List<LlmGatewayClient.LlmCallResult> calls = new java.util.ArrayList<>();
        Supplier<String> invocation = () -> regularChat(systemPrompt, untrustedInput,
                maxTokensFor(promptName), responseFormat, calls);
        Supplier<String> retryInvocation = () -> regularChat(systemPrompt, untrustedInput,
                retryMaxTokensFor(promptName), responseFormat, calls);
        String response;
        if ("planner".equals(promptName)) {
            response = withSingleContractRetry(invocation, retryInvocation, agentResponses::hasValidPlannerContract,
                    reason -> {
                        plannerContractRetries.increment();
                        log.warn("Task {} ({}) planner retrying once; reason={}", state.id, state.ticketNumber,
                                reason);
                    });
        } else {
            response = withSingleRetryableCompletion(invocation, retryInvocation, reason -> {
                operationalMetrics.retry("agent");
                log.warn("Task {} ({}) {} retrying once with a larger output budget; reason={}",
                        state.id, state.ticketNumber, promptName, reason);
            });
        }
        log.info("Task {} ({}) {} agent completed; response_chars={}", state.id, state.ticketNumber,
                promptName, response.length());
        long tokens = calls.stream().mapToLong(LlmGatewayClient.LlmCallResult::tokens).sum();
        long costMicros = calls.stream().mapToLong(LlmGatewayClient.LlmCallResult::costMicros).sum();
        return new AgentOutput(response, new PipelineProjectionEvent.AgentMetadata(
                Map.of(promptName, fingerprint), tokens, costMicros, 0));
    }

    private String regularChat(String systemPrompt, String input, int maxTokens,
                               Map<String, Object> responseFormat, List<LlmGatewayClient.LlmCallResult> calls) {
        LlmGatewayClient.LlmCallResult result = llm.chatDetailed(systemPrompt, input, maxTokens, responseFormat);
        calls.add(result);
        return result.content();
    }

    public void writeRunMetadata(Path workspace, TaskState state) throws Exception {
        writeMetadata(workspace, state);
    }

    private record AgentOutput(String content, PipelineProjectionEvent.AgentMetadata metadata) {}

    private record PatchRepairResult(String patch, int repairs,
                                     PipelineProjectionEvent.AgentMetadata agentMetadata) {}

    static String stripFence(String value) {
        return PatchIntegrator.stripFence(value);
    }

    static <T> T withSingleContractRetry(Supplier<T> invocation, Supplier<T> retryInvocation,
                                         Predicate<T> contract,
                                         java.util.function.Consumer<String> retryObserver) {
        T response;
        try {
            response = invocation.get();
        } catch (LlmCompletionException exception) {
            if (!exception.retryable()) throw exception;
            retryObserver.accept(exception.reason());
            return retryInvocation.get();
        }
        if (contract.test(response)) return response;
        retryObserver.accept("invalid_contract");
        return retryInvocation.get();
    }

    static String withSingleRetryableCompletion(Supplier<String> invocation, Supplier<String> retryInvocation,
                                                java.util.function.Consumer<String> retryObserver) {
        try {
            return invocation.get();
        } catch (LlmCompletionException exception) {
            if (!exception.retryable()) throw exception;
            retryObserver.accept(exception.reason());
            return retryInvocation.get();
        }
    }

    static int retryMaxTokensFor(String promptName) {
        return switch (promptName) {
            case "planner" -> 2_400;
            case "patch-repair" -> 3_200;
            default -> 2_400;
        };
    }

    static int maxTokensFor(String promptName) {
        return switch (promptName) {
            case "planner", "developer", "tester", "reviewer", "reviewer-prod" -> 1_200;
            case "patch-repair" -> 1_600;
            default -> 1_200;
        };
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
