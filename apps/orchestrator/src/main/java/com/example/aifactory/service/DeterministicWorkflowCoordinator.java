package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.*;
import com.example.aifactory.workflow.WorkflowCoordinator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DeterministicWorkflowCoordinator implements WorkflowCoordinator {
    private static final Pattern PATCH_FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE);
    private static final int MAX_PATCH_REPAIR_ATTEMPTS = 2;
    private static final Logger log = LoggerFactory.getLogger(DeterministicWorkflowCoordinator.class);
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
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
    private final Counter completedTasks;
    private final Counter failedTasks;
    private final Counter plannerContractRetries;
    private final ObjectMapper objectMapper;
    private final com.example.aifactory.config.AgentToolingProperties agentTooling;
    private final AgentContextToolHost agentTools;
    private final WorkflowOperationalMetrics operationalMetrics;

    public DeterministicWorkflowCoordinator(AiFactoryProperties props, ProcessRunner runner, RepositoryContextProvider contextService,
                       PromptService prompts, LlmGatewayClient llm, AgentResponseValidator agentResponses,
                       SandboxExecutor sandbox, PatchIntegrator patchIntegrator,
                       AssuranceGateway assurance, ScmDeliveryGateway scmDelivery,
                       MeterRegistry metrics, ObjectMapper objectMapper,
                       com.example.aifactory.config.AgentToolingProperties agentTooling,
                       AgentContextToolHost agentTools) {
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
        this.completedTasks = Counter.builder("ai_factory_tasks_completed").description("Tasks that completed validation").register(metrics);
        this.failedTasks = Counter.builder("ai_factory_tasks_failed").description("Tasks that failed before approval").register(metrics);
        this.plannerContractRetries = Counter.builder("ai_factory_planner_contract_retries")
                .description("Planner calls retried once after an invalid response contract")
                .register(metrics);
    }

    @Override
    public void start(TaskState state) {
        executor.submit(() -> runPipeline(state));
    }

    @Override
    public void resumeAfterApproval(TaskState state) {
        executor.submit(() -> {
            try {
                Path workspace = Path.of(state.workspace);
                String prUrl = scmDelivery.createDraftPullRequest(workspace, state.request.repositoryUrl(),
                        state.request.effectiveBranch(), state.id, state.sourceCommit, state.request.requirement());
                state.pullRequestUrl = prUrl;
                state.transition(TaskStatus.PR_CREATED, "Pull request created: " + prUrl);
                log.info("Task {} ({}) created pull request", state.id, state.ticketNumber);
            } catch (Exception e) { state.fail(e); }
        });
    }

    private void runPipeline(TaskState s) {
        try {
            Path root = Path.of(props.workspaceRoot());
            Files.createDirectories(root);
            Path ws = root.resolve(s.id).toAbsolutePath();
            Files.createDirectories(ws);
            s.workspace = ws.toString();
            log.info("Task {} ({}) workspace initialized", s.id, s.ticketNumber);

            s.transition(TaskStatus.CLONING, "Cloning repository");
            runner.run(List.of("git", "clone", "--depth", "1", "--branch", s.request.effectiveBranch(), s.request.repositoryUrl(), ws.toString()), null, Duration.ofMinutes(2));
            s.sourceCommit = runner.run(List.of("git", "rev-parse", "HEAD"), ws, Duration.ofSeconds(10)).strip();
            s.model = llm.modelName();
            log.info("Task {} ({}) cloned source commit {} using model {}", s.id, s.ticketNumber, s.sourceCommit, s.model);
            String plannerContext = agentTooling.enabledFor("planner")
                    ? "Use the authorized context tools to retrieve only the repository evidence needed for this plan."
                    : contextService.collectForRole(ws, s.id, s.sourceCommit, "planner");
            writeRunMetadata(ws, s);

            s.transition(TaskStatus.PLANNING, "Planner agent analyzing requirement and repository context");
            s.plan = chat(s, "planner", untrusted("REQUIREMENT", s.request.requirement()) + untrusted("REPOSITORY_CONTEXT", plannerContext));
            agentResponses.requireImplementablePlan(s.plan);
            Files.writeString(ws.resolve(".ai-plan.md"), s.plan);
            writeRunMetadata(ws, s);

            s.transition(TaskStatus.GENERATING_PATCH, "Developer agent generating a unified diff");
            String developerContext = contextService.collectForRole(ws, s.id, s.sourceCommit, "developer");
            String rawPatch = chat(s, "developer", untrusted("REQUIREMENT", s.request.requirement()) +
                    untrusted("PLAN", s.plan) + untrusted("REPOSITORY_CONTEXT", developerContext));
            writeRunMetadata(ws, s);
            s.patch = validateAndRepairPatch(s, ws, rawPatch);

            s.transition(TaskStatus.APPLYING_PATCH, "Applying generated patch inside isolated Docker sandbox");
            patchIntegrator.apply(ws, s.id, s.sourceCommit,
                    new PatchIntegrator.IntegratedPatch(s.patch, PatchIntegrator.digestFor(s.patch)));

            s.transition(TaskStatus.TESTING, "Running deterministic build and tests in sandbox");
            String deterministicTests = tail(sandbox.test(ws, s.id, s.sourceCommit), 12000);
            String testerReview = chat(s, "tester", untrusted("REQUIREMENT", s.request.requirement()) +
                    untrusted("PATCH", s.patch) + untrusted("DETERMINISTIC_TEST_EVIDENCE", deterministicTests));
            agentResponses.requireTesterReport(testerReview);
            writeRunMetadata(ws, s);
            s.testSummary = deterministicTests + "\n\n--- AI TESTER REVIEW ---\n" + testerReview;
            s.testsPassed = true;
            Files.createDirectories(ws.resolve(".ai-factory"));
            Files.writeString(ws.resolve(".ai-factory/test.txt"), s.testSummary);
            s.assuranceResults.put("tests", evidenceResult(s, "tests", "PASSED", deterministicTests));

            s.transition(TaskStatus.QUALITY_SCANNING, "Running SonarQube quality analysis");
            s.qualitySummary = tail(sandbox.quality(ws, s.id, s.sourceCommit), 12000);
            JsonNode qualityResult = assurance.requireQualityGate(s.id, s.sourceCommit, s.qualitySummary);
            s.assuranceResults.put("quality", objectMapper.convertValue(qualityResult, Map.class));

            s.transition(TaskStatus.SECURITY_SCANNING, "Generating SBOM and running Trivy");
            s.securitySummary = tail(sandbox.security(ws, s.id, s.sourceCommit), 12000);
            s.assuranceResults.put("security", evidenceResult(s, "security", "PASSED", s.securitySummary));
            Path sbom = ws.resolve(".ai-factory/sbom.cdx.json");
            s.assuranceResults.put("sbom", Map.of("schema_version", "1", "task_id", s.id,
                    "attempt_id", "pipeline-1", "source_commit", s.sourceCommit, "format", "CYCLONEDX_JSON",
                    "uri", "evidence://" + s.id + "/pipeline-1/sbom", "digest", sha256(Files.readAllBytes(sbom)),
                    "status", "COMPLETE"));

            s.transition(TaskStatus.REVIEWING, "Reviewer agent assessing plan, patch and deterministic evidence");
            s.review = chat(s, "reviewer", untrusted("REQUIREMENT", s.request.requirement()) + untrusted("PLAN", s.plan) +
                    untrusted("PATCH", s.patch) + untrusted("ASSURANCE_RESULTS", objectMapper.writeValueAsString(s.assuranceResults)));
            AgentResponseValidator.ReviewSummary reviewSummary = agentResponses.summarizeReview(s.review);
            logReviewerDecision(s, reviewSummary);
            agentResponses.requireReviewAllowsApproval(reviewSummary);
            s.reviewAccepted = true;
            Files.writeString(ws.resolve(".ai-review.md"), s.review);
            writeRunMetadata(ws, s);

            s.pendingEffect = new PendingEffect(
                    "scm.create_draft_pull_request",
                    Map.of("base_branch", s.request.effectiveBranch(),
                            "repository", safeRepositoryLabel(s.request.repositoryUrl()),
                            "title", "[" + s.ticketNumber + "] " + conciseRequirement(s.request.requirement())),
                    "Créera une branche distante, un commit et une pull request brouillon dans le dépôt indiqué.",
                    "ALLOW", true);
            s.transition(TaskStatus.WAITING_APPROVAL, "Pipeline complete. Human approval required before commit/push/PR.");
            completedTasks.increment();
            log.info("Task {} ({}) completed all automated stages and awaits approval", s.id, s.ticketNumber);
        } catch (Exception e) {
            failedTasks.increment();
            s.fail(e);
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

    private void logReviewerDecision(TaskState state, AgentResponseValidator.ReviewSummary review) {
        if (review.findings().isEmpty()) {
            log.info("Task {} ({}) reviewer decision={}; no findings reported",
                    state.id, state.ticketNumber, review.decision());
            return;
        }
        log.warn("Task {} ({}) reviewer decision={}; findings: {}",
                state.id, state.ticketNumber, review.decision(), review.findingCounts());
        for (int index = 0; index < review.findings().size(); index++) {
            AgentResponseValidator.ReviewFinding finding = review.findings().get(index);
            log.warn("Task {} ({}) reviewer finding {}/{}: severity={}, file={}, rule={}, recommended_fix={}",
                    state.id, state.ticketNumber, index + 1, review.findings().size(),
                    logField(finding.severity()), logField(finding.file()), logField(finding.rule()), logField(finding.fix()));
        }
    }

    private static String logField(String value) {
        String normalized = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.length() <= 400 ? normalized : normalized.substring(0, 397) + "...";
    }

    private String validateAndRepairPatch(TaskState state, Path workspace, String rawPatch) throws Exception {
        String patch = PatchIntegrator.normalize(rawPatch);
        for (int repairAttempt = 0; repairAttempt <= MAX_PATCH_REPAIR_ATTEMPTS; repairAttempt++) {
            try {
                return patchIntegrator.validate(workspace, state.id, state.sourceCommit, patch).content();
            } catch (Exception validationFailure) {
                if (repairAttempt == MAX_PATCH_REPAIR_ATTEMPTS) {
                    throw validationFailure;
                }
                state.patchRepairs++;
                log.warn("Task {} ({}) patch validation failed; starting repair attempt {}/{}: {}",
                        state.id, state.ticketNumber, repairAttempt + 1, MAX_PATCH_REPAIR_ATTEMPTS, validationFailure.getMessage());
                operationalMetrics.repair();
                Files.writeString(workspace.resolve("changes.invalid.patch"), patch);
                String repairRepositoryContext = contextService.collectForRole(
                        workspace, state.id, state.sourceCommit, "patch-repair");
                String repaired = chat(state, "patch-repair", untrusted("REQUIREMENT", state.request.requirement()) +
                        untrusted("PLAN", state.plan) + untrusted("REPOSITORY_CONTEXT", repairRepositoryContext) +
                        untrusted("CURRENT_FILE_CONTENTS", affectedFileContext(workspace, patch)) +
                        untrusted("INVALID_PATCH", patch) + untrusted("GIT_APPLY_ERROR", validationFailure.getMessage()) +
                        untrusted("REPAIR_ATTEMPT", Integer.toString(repairAttempt + 1)));
                writeRunMetadata(workspace, state);
                patch = PatchIntegrator.normalize(repaired);
            }
        }
        throw new IllegalStateException("Patch validation exited unexpectedly");
    }

    static String stripFence(String s) {
        return PatchIntegrator.stripFence(s);
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

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "...[truncated]...\n" + s.substring(s.length() - max);
    }

    private static Map<String, Object> evidenceResult(TaskState state, String type, String verdict, String content) {
        return Map.of("schema_version", "1", "task_id", state.id, "attempt_id", "pipeline-1",
                "source_commit", state.sourceCommit, "type", type, "verdict", verdict,
                "evidence", Map.of("uri", "evidence://" + state.id + "/pipeline-1/" + type,
                        "digest", sha256(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "status", "COMPLETE"));
    }

    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) { throw new IllegalStateException("cannot digest assurance result", exception); }
    }

    private String chat(TaskState state, String promptName, String untrustedInput) {
        String fingerprint = prompts.fingerprint(promptName);
        state.promptFingerprints.put(promptName, fingerprint);
        log.info("Task {} ({}) invoking {} agent with prompt sha256={}",
                state.id, state.ticketNumber, promptName, fingerprint.substring(0, 12));
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
                        log.warn("Task {} ({}) planner retrying once; reason={}",
                                state.id, state.ticketNumber, reason);
                    });
        } else if ("patch-repair".equals(promptName)) {
            response = withSingleRetryableCompletion(invocation, retryInvocation, reason -> {
                operationalMetrics.retry("agent");
                log.warn("Task {} ({}) patch-repair retrying once with a larger output budget; reason={}",
                        state.id, state.ticketNumber, reason);
            });
        } else {
            response = withSingleRetryableCompletion(invocation, retryInvocation, reason -> {
                operationalMetrics.retry("agent");
                log.warn("Task {} ({}) {} retrying once with a larger output budget; reason={}",
                        state.id, state.ticketNumber, promptName, reason);
            });
        }
        log.info("Task {} ({}) {} agent completed; response_chars={}",
                state.id, state.ticketNumber, promptName, response.length());
        return response;
    }

    private String regularChat(TaskState state, String systemPrompt, String input, int maxTokens,
                               Map<String, Object> responseFormat) {
        LlmGatewayClient.LlmCallResult result = llm.chatDetailed(systemPrompt, input, maxTokens, responseFormat);
        state.recordAgentUsage(0, result.tokens(), result.costMicros());
        return result.content();
    }

    static String withSingleContractRetry(Supplier<String> invocation, Supplier<String> retryInvocation,
                                          Predicate<String> contract,
                                          java.util.function.Consumer<String> retryObserver) {
        String response;
        try {
            response = invocation.get();
        } catch (LlmCompletionException exception) {
            if (!exception.retryable()) {
                throw exception;
            }
            retryObserver.accept(exception.reason());
            return retryInvocation.get();
        }
        if (contract.test(response)) {
            return response;
        }
        retryObserver.accept("invalid_contract");
        return retryInvocation.get();
    }

    static String withSingleRetryableCompletion(Supplier<String> invocation, Supplier<String> retryInvocation,
                                                java.util.function.Consumer<String> retryObserver) {
        try {
            return invocation.get();
        } catch (LlmCompletionException exception) {
            if (!exception.retryable()) {
                throw exception;
            }
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
            case "planner" -> 1_200;
            case "developer" -> 1_200;
            case "patch-repair" -> 1_600;
            case "tester", "reviewer", "reviewer-prod" -> 1_200;
            default -> 1_200;
        };
    }

    static String untrusted(String label, String content) {
        if (label == null || !label.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid untrusted-data label");
        }
        String escaped = (content == null ? "" : content)
                .replace("</" + label + ">", "&lt;/" + label + "&gt;");
        return "\n<" + label + " trust=\"untrusted\">\n" + escaped
                + "\n</" + label + ">\n";
    }

    private static void writeRunMetadata(Path workspace, TaskState state) throws Exception {
        Files.createDirectories(workspace.resolve(".ai-factory"));
        String prompts = state.promptFingerprints.entrySet().stream()
                .map(entry -> "    \"" + entry.getKey() + "\": \"" + entry.getValue() + "\"")
                .collect(java.util.stream.Collectors.joining(",\n"));
        String metadata = "{\n" +
                "  \"ticket_number\": \"" + state.ticketNumber + "\",\n" +
                "  \"source_commit\": \"" + state.sourceCommit + "\",\n" +
                "  \"model\": \"" + state.model + "\",\n" +
                "  \"prompts\": {\n" + prompts + "\n  }\n}\n";
        Files.writeString(workspace.resolve(".ai-factory/run-metadata.json"), metadata);
    }

}
