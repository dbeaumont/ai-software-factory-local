package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TaskService {
    private static final Pattern PATCH_FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$", Pattern.MULTILINE);
    private static final int MAX_PATCH_REPAIR_ATTEMPTS = 2;
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger ticketSequence = new AtomicInteger(1);
    private final AiFactoryProperties props;
    private final ProcessRunner runner;
    private final RepositoryContextProvider contextService;
    private final PromptService prompts;
    private final LlmGatewayClient llm;
    private final AgentResponseValidator agentResponses;
    private final SandboxExecutor sandbox;
    private final GiteaService gitea;
    private final Counter submittedTasks;
    private final Counter completedTasks;
    private final Counter failedTasks;

    public TaskService(AiFactoryProperties props, ProcessRunner runner, RepositoryContextProvider contextService,
                       PromptService prompts, LlmGatewayClient llm, AgentResponseValidator agentResponses, SandboxExecutor sandbox, GiteaService gitea,
                       MeterRegistry metrics) {
        this.props = props;
        this.runner = runner;
        this.contextService = contextService;
        this.prompts = prompts;
        this.llm = llm;
        this.agentResponses = agentResponses;
        this.sandbox = sandbox;
        this.gitea = gitea;
        this.submittedTasks = Counter.builder("ai_factory_tasks_submitted").description("Tasks submitted to the factory").register(metrics);
        this.completedTasks = Counter.builder("ai_factory_tasks_completed").description("Tasks that completed validation").register(metrics);
        this.failedTasks = Counter.builder("ai_factory_tasks_failed").description("Tasks that failed before approval").register(metrics);
    }

    public TaskView create(TaskRequest request) {
        if (request.repositoryUrl() == null || request.repositoryUrl().isBlank()) throw new IllegalArgumentException("repositoryUrl is required");
        if (request.requirement() == null || request.requirement().isBlank()) throw new IllegalArgumentException("requirement is required");
        if (request.effectiveLlmMode() == LlmMode.CLOUD && !props.cloudEnabled()) {
            throw new IllegalArgumentException("Cloud LLM is disabled by configuration");
        }
        if (request.effectiveLlmMode() == LlmMode.CLOUD) {
            CloudAvailability availability = llm.cloudAvailability();
            if (!availability.available()) {
                throw new IllegalStateException(availability.error());
            }
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String ticketNumber = nextTicketNumber();
        TaskState state = new TaskState(id, ticketNumber, request);
        tasks.put(id, state);
        submittedTasks.increment();
        log.info("Task {} ({}) accepted: mode={}, branch={}", id, ticketNumber, request.effectiveLlmMode(), request.effectiveBranch());
        executor.submit(() -> runPipeline(state));
        return state.view();
    }

    public TaskView get(String id) {
        TaskState s = tasks.get(id);
        if (s == null) throw new IllegalArgumentException("Unknown task " + id);
        return s.view();
    }

    public List<TaskView> list() { return tasks.values().stream().map(TaskState::view).toList(); }

    public TaskView approve(String id) {
        TaskState state = tasks.get(id);
        if (state == null) throw new IllegalArgumentException("Unknown task " + id);
        if (state.status != TaskStatus.WAITING_APPROVAL) throw new IllegalStateException("Task is not waiting for approval");
        log.info("Task {} ({}) approved by the delivery workflow", state.id, state.ticketNumber);
        state.transition(TaskStatus.APPROVED, "Human approval recorded");
        executor.submit(() -> {
            try {
                Path workspace = Path.of(state.workspace);
                String prUrl = gitea.commitPushAndCreatePr(workspace, state.request.repositoryUrl(), state.request.effectiveBranch(), state.id, state.request.requirement());
                state.pullRequestUrl = prUrl;
                state.transition(TaskStatus.PR_CREATED, "Pull request created: " + prUrl);
                log.info("Task {} ({}) created pull request", state.id, state.ticketNumber);
            } catch (Exception e) { state.fail(e); }
        });
        return state.view();
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
            s.model = llm.modelName(s.request.effectiveLlmMode());
            log.info("Task {} ({}) cloned source commit {} using model {}", s.id, s.ticketNumber, s.sourceCommit, s.model);
            String context = contextService.collect(ws, s.id, s.sourceCommit);
            writeRunMetadata(ws, s);

            s.transition(TaskStatus.PLANNING, "Planner agent analyzing requirement and repository context");
            s.plan = chat(s, "planner", untrusted("REQUIREMENT", s.request.requirement()) + untrusted("REPOSITORY_CONTEXT", context));
            agentResponses.requireImplementablePlan(s.plan);
            Files.writeString(ws.resolve(".ai-plan.md"), s.plan);
            writeRunMetadata(ws, s);

            s.transition(TaskStatus.GENERATING_PATCH, "Developer agent generating a unified diff");
            String rawPatch = chat(s, "developer", untrusted("REQUIREMENT", s.request.requirement()) +
                    untrusted("PLAN", s.plan) + untrusted("REPOSITORY_CONTEXT", context));
            writeRunMetadata(ws, s);
            s.patch = validateAndRepairPatch(s, ws, rawPatch);

            s.transition(TaskStatus.APPLYING_PATCH, "Applying generated patch inside isolated Docker sandbox");
            sandbox.applyPatch(ws, s.id, s.sourceCommit);

            s.transition(TaskStatus.TESTING, "Running deterministic build and tests in sandbox");
            String deterministicTests = tail(sandbox.test(ws, s.id, s.sourceCommit), 12000);
            String testerReview = chat(s, "tester", untrusted("REQUIREMENT", s.request.requirement()) +
                    untrusted("PATCH", s.patch) + untrusted("DETERMINISTIC_TEST_EVIDENCE", deterministicTests));
            agentResponses.requireTesterReport(testerReview);
            writeRunMetadata(ws, s);
            s.testSummary = deterministicTests + "\n\n--- AI TESTER REVIEW ---\n" + testerReview;
            Files.createDirectories(ws.resolve(".ai-factory"));
            Files.writeString(ws.resolve(".ai-factory/test.txt"), s.testSummary);

            s.transition(TaskStatus.QUALITY_SCANNING, "Running SonarQube quality analysis");
            s.qualitySummary = tail(sandbox.quality(ws, s.id, s.sourceCommit), 12000);
            requireQualityGate(s.qualitySummary);

            s.transition(TaskStatus.SECURITY_SCANNING, "Generating SBOM and running Trivy");
            s.securitySummary = tail(sandbox.security(ws, s.id, s.sourceCommit), 12000);

            s.transition(TaskStatus.REVIEWING, "Reviewer agent assessing plan, patch and deterministic evidence");
            s.review = chat(s, "reviewer", untrusted("REQUIREMENT", s.request.requirement()) + untrusted("PLAN", s.plan) +
                    untrusted("PATCH", s.patch) + untrusted("TEST_EVIDENCE", s.testSummary) +
                    untrusted("QUALITY_EVIDENCE", s.qualitySummary) + untrusted("SECURITY_EVIDENCE", s.securitySummary));
            AgentResponseValidator.ReviewSummary reviewSummary = agentResponses.summarizeReview(s.review);
            logReviewerDecision(s, reviewSummary);
            agentResponses.requireReviewAllowsApproval(reviewSummary);
            Files.writeString(ws.resolve(".ai-review.md"), s.review);
            writeRunMetadata(ws, s);

            s.transition(TaskStatus.WAITING_APPROVAL, "Pipeline complete. Human approval required before commit/push/PR.");
            completedTasks.increment();
            log.info("Task {} ({}) completed all automated stages and awaits approval", s.id, s.ticketNumber);
        } catch (Exception e) {
            failedTasks.increment();
            s.fail(e);
        }
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
        String patch = UnifiedDiffNormalizer.normalize(stripFence(rawPatch));
        for (int repairAttempt = 0; repairAttempt <= MAX_PATCH_REPAIR_ATTEMPTS; repairAttempt++) {
            Files.writeString(workspace.resolve("changes.patch"), patch);
            try {
                sandbox.checkPatch(workspace, state.id, state.sourceCommit);
                return patch;
            } catch (Exception validationFailure) {
                if (repairAttempt == MAX_PATCH_REPAIR_ATTEMPTS) {
                    throw validationFailure;
                }
                log.warn("Task {} ({}) patch validation failed; starting repair attempt {}/{}: {}",
                        state.id, state.ticketNumber, repairAttempt + 1, MAX_PATCH_REPAIR_ATTEMPTS, validationFailure.getMessage());
                Files.writeString(workspace.resolve("changes.invalid.patch"), patch);
                String repaired = chat(state, "patch-repair", untrusted("REQUIREMENT", state.request.requirement()) +
                        untrusted("PLAN", state.plan) + untrusted("CURRENT_FILE_CONTENTS", affectedFileContext(workspace, patch)) +
                        untrusted("INVALID_PATCH", patch) + untrusted("GIT_APPLY_ERROR", validationFailure.getMessage()) +
                        untrusted("REPAIR_ATTEMPT", Integer.toString(repairAttempt + 1)));
                writeRunMetadata(workspace, state);
                patch = UnifiedDiffNormalizer.normalize(stripFence(repaired));
            }
        }
        throw new IllegalStateException("Patch validation exited unexpectedly");
    }

    static String stripFence(String s) {
        String out = s.strip();
        int fenceStart = out.indexOf("```");
        if (fenceStart >= 0) {
            int firstNewline = out.indexOf('\n', fenceStart);
            if (firstNewline >= 0) out = out.substring(firstNewline + 1);
            if (out.endsWith("```")) out = out.substring(0, out.length() - 3);
        }
        int diffStart = out.indexOf("diff --git ");
        if (diffStart > 0) out = out.substring(diffStart);
        return out.strip();
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

    static void requireQualityGate(String qualityEvidence) {
        if (qualityEvidence == null || qualityEvidence.startsWith("Skipped")) {
            throw new IllegalStateException("Required deterministic quality gate did not run");
        }
    }

    private String chat(TaskState state, String promptName, String untrustedInput) {
        String fingerprint = prompts.fingerprint(promptName);
        state.promptFingerprints.put(promptName, fingerprint);
        log.info("Task {} ({}) invoking {} agent with prompt sha256={}",
                state.id, state.ticketNumber, promptName, fingerprint.substring(0, 12));
        String response = llm.chat(state.request.effectiveLlmMode(), prompts.load(promptName), untrustedInput);
        log.info("Task {} ({}) {} agent completed; response_chars={}",
                state.id, state.ticketNumber, promptName, response.length());
        return response;
    }

    private static String untrusted(String label, String content) {
        return "\n<" + label + " trust=\"untrusted\">\n" + (content == null ? "" : content)
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

    String nextTicketNumber() {
        return "AF-%04d".formatted(ticketSequence.getAndIncrement());
    }
}
