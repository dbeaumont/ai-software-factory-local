package com.example.aifactory.service;

import com.example.aifactory.config.AgentToolingProperties;
import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskState;
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
    private final WorkflowOperationalMetrics operationalMetrics;
    private final Counter plannerContractRetries;

    public PipelineStepService(AiFactoryProperties props, ProcessRunner runner,
                               RepositoryContextProvider contextService, PromptService prompts,
                               LlmGatewayClient llm, AgentResponseValidator agentResponses,
                               SandboxExecutor sandbox, PatchIntegrator patchIntegrator,
                               AssuranceGateway assurance, ScmDeliveryGateway scmDelivery,
                               MeterRegistry metrics, ObjectMapper objectMapper,
                               AgentToolingProperties agentTooling, AgentContextToolHost agentTools) {
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
        this.operationalMetrics = new WorkflowOperationalMetrics(metrics);
        this.plannerContractRetries = Counter.builder("ai_factory_planner_contract_retries")
                .description("Planner calls retried once after an invalid response contract")
                .register(metrics);
    }

    public Path initializeWorkspace(TaskState state) throws Exception {
        Path root = Path.of(props.workspaceRoot());
        Files.createDirectories(root);
        Path workspace = root.resolve(state.id).toAbsolutePath();
        Files.createDirectories(workspace);
        state.workspace = workspace.toString();
        log.info("Task {} ({}) workspace initialized", state.id, state.ticketNumber);
        return workspace;
    }

    public void cloneSource(TaskState state, Path workspace) throws Exception {
        runner.run(List.of("git", "clone", "--depth", "1", "--branch", state.request.effectiveBranch(),
                state.request.repositoryUrl(), workspace.toString()), null, Duration.ofMinutes(2));
        state.sourceCommit = runner.run(List.of("git", "rev-parse", "HEAD"), workspace,
                Duration.ofSeconds(10)).strip();
        state.model = llm.modelName();
        writeRunMetadata(workspace, state);
        log.info("Task {} ({}) cloned source commit {} using model {}", state.id, state.ticketNumber,
                state.sourceCommit, state.model);
    }

    public void plan(TaskState state, Path workspace) throws Exception {
        String plannerContext = agentTooling.enabledFor("planner")
                ? "Use the authorized context tools to retrieve only the repository evidence needed for this plan."
                : contextService.collectForRole(workspace, state.id, state.sourceCommit, "planner");
        state.plan = chat(state, "planner", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("REPOSITORY_CONTEXT", plannerContext));
        agentResponses.requireImplementablePlan(state.plan);
        Files.writeString(workspace.resolve(".ai-plan.md"), state.plan);
        writeRunMetadata(workspace, state);
    }

    public void generateAndRepairPatch(TaskState state, Path workspace) throws Exception {
        String developerContext = contextService.collectForRole(workspace, state.id, state.sourceCommit, "developer");
        String rawPatch = chat(state, "developer", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", developerContext));
        writeRunMetadata(workspace, state);
        state.patch = validateAndRepairPatch(state, workspace, rawPatch);
    }

    public void applyPatch(TaskState state, Path workspace) throws Exception {
        patchIntegrator.apply(workspace, state.id, state.sourceCommit,
                new PatchIntegrator.IntegratedPatch(state.patch, PatchIntegrator.digestFor(state.patch)));
    }

    public void test(TaskState state, Path workspace) throws Exception {
        String deterministicTests = tail(sandbox.test(workspace, state.id, state.sourceCommit), 12_000);
        String testerReview = chat(state, "tester", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PATCH", state.patch)
                + untrusted("DETERMINISTIC_TEST_EVIDENCE", deterministicTests));
        agentResponses.requireTesterReport(testerReview);
        writeRunMetadata(workspace, state);
        state.testSummary = deterministicTests + "\n\n--- AI TESTER REVIEW ---\n" + testerReview;
        state.testsPassed = true;
        Files.createDirectories(workspace.resolve(".ai-factory"));
        Files.writeString(workspace.resolve(".ai-factory/test.txt"), state.testSummary);
        state.assuranceResults.put("tests", evidenceResult(state, "tests", "PASSED", deterministicTests));
    }

    public void quality(TaskState state, Path workspace) throws Exception {
        state.qualitySummary = tail(sandbox.quality(workspace, state.id, state.sourceCommit), 12_000);
        JsonNode result = assurance.requireQualityGate(state.id, state.sourceCommit, state.qualitySummary);
        state.assuranceResults.put("quality", objectMapper.convertValue(result, Map.class));
    }

    public void security(TaskState state, Path workspace) throws Exception {
        state.securitySummary = tail(sandbox.security(workspace, state.id, state.sourceCommit), 12_000);
        state.assuranceResults.put("security", evidenceResult(state, "security", "PASSED", state.securitySummary));
        Path sbom = workspace.resolve(".ai-factory/sbom.cdx.json");
        state.assuranceResults.put("sbom", Map.of("schema_version", "1", "task_id", state.id,
                "attempt_id", "pipeline-1", "source_commit", state.sourceCommit, "format", "CYCLONEDX_JSON",
                "uri", "evidence://" + state.id + "/pipeline-1/sbom", "digest", sha256(Files.readAllBytes(sbom)),
                "status", "COMPLETE"));
    }

    public void review(TaskState state, Path workspace) throws Exception {
        state.review = chat(state, "reviewer", untrusted("REQUIREMENT", state.request.requirement())
                + untrusted("PLAN", state.plan) + untrusted("PATCH", state.patch)
                + untrusted("ASSURANCE_RESULTS", objectMapper.writeValueAsString(state.assuranceResults)));
        AgentResponseValidator.ReviewSummary summary = agentResponses.summarizeReview(state.review);
        logReviewerDecision(state, summary);
        agentResponses.requireReviewAllowsApproval(summary);
        state.reviewAccepted = true;
        Files.writeString(workspace.resolve(".ai-review.md"), state.review);
        writeRunMetadata(workspace, state);
    }

    public void prepareDelivery(TaskState state) {
        state.pendingEffect = new PendingEffect("scm.create_draft_pull_request",
                Map.of("base_branch", state.request.effectiveBranch(),
                        "repository", safeRepositoryLabel(state.request.repositoryUrl()),
                        "title", "[" + state.ticketNumber + "] " + conciseRequirement(state.request.requirement())),
                "Créera une branche distante, un commit et une pull request brouillon dans le dépôt indiqué.",
                "ALLOW", true);
    }

    public void deliver(TaskState state) throws Exception {
        Path workspace = Path.of(state.workspace);
        state.pullRequestUrl = scmDelivery.createDraftPullRequest(workspace, state.request.repositoryUrl(),
                state.request.effectiveBranch(), state.id, state.sourceCommit, state.request.requirement());
    }

    private String validateAndRepairPatch(TaskState state, Path workspace, String rawPatch) throws Exception {
        String patch = PatchIntegrator.normalize(rawPatch);
        for (int repairAttempt = 0; repairAttempt <= MAX_PATCH_REPAIR_ATTEMPTS; repairAttempt++) {
            try {
                return patchIntegrator.validate(workspace, state.id, state.sourceCommit, patch).content();
            } catch (Exception validationFailure) {
                if (repairAttempt == MAX_PATCH_REPAIR_ATTEMPTS) throw validationFailure;
                state.patchRepairs++;
                operationalMetrics.repair();
                log.warn("Task {} ({}) patch validation failed; starting repair attempt {}/{}: {}",
                        state.id, state.ticketNumber, repairAttempt + 1, MAX_PATCH_REPAIR_ATTEMPTS,
                        validationFailure.getMessage());
                Files.writeString(workspace.resolve("changes.invalid.patch"), patch);
                String context = contextService.collectForRole(workspace, state.id, state.sourceCommit,
                        "patch-repair");
                String repaired = chat(state, "patch-repair", untrusted("REQUIREMENT", state.request.requirement())
                        + untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", context)
                        + untrusted("CURRENT_FILE_CONTENTS", affectedFileContext(workspace, patch))
                        + untrusted("INVALID_PATCH", patch)
                        + untrusted("GIT_APPLY_ERROR", validationFailure.getMessage())
                        + untrusted("REPAIR_ATTEMPT", Integer.toString(repairAttempt + 1)));
                writeRunMetadata(workspace, state);
                patch = PatchIntegrator.normalize(repaired);
            }
        }
        throw new IllegalStateException("Patch validation exited unexpectedly");
    }

    private String chat(TaskState state, String promptName, String untrustedInput) {
        String fingerprint = prompts.fingerprint(promptName);
        state.promptFingerprints.put(promptName, fingerprint);
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
            state.recordAgentUsage(result.turns(), result.tokens(), result.costMicros());
            log.info("Task {} ({}) {} tool loop completed; turns={} tokens={} duration_ms={}",
                    state.id, state.ticketNumber, promptName, result.turns(), result.tokens(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis());
            return result.finalResult();
        }
        Map<String, Object> responseFormat = PlannerResponseFormat.forPrompt(promptName);
        Supplier<String> invocation = () -> regularChat(state, systemPrompt, untrustedInput,
                maxTokensFor(promptName), responseFormat);
        Supplier<String> retryInvocation = () -> regularChat(state, systemPrompt, untrustedInput,
                retryMaxTokensFor(promptName), responseFormat);
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
        return response;
    }

    private String regularChat(TaskState state, String systemPrompt, String input, int maxTokens,
                               Map<String, Object> responseFormat) {
        LlmGatewayClient.LlmCallResult result = llm.chatDetailed(systemPrompt, input, maxTokens, responseFormat);
        state.recordAgentUsage(0, result.tokens(), result.costMicros());
        return result.content();
    }

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

    private static Map<String, Object> evidenceResult(TaskState state, String type, String verdict, String content) {
        return Map.of("schema_version", "1", "task_id", state.id, "attempt_id", "pipeline-1",
                "source_commit", state.sourceCommit, "type", type, "verdict", verdict,
                "evidence", Map.of("uri", "evidence://" + state.id + "/pipeline-1/" + type,
                        "digest", sha256(content.getBytes(StandardCharsets.UTF_8)), "status", "COMPLETE"));
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

    private static void writeRunMetadata(Path workspace, TaskState state) throws Exception {
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
