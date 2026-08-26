package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AtomicInteger ticketSequence = new AtomicInteger(1);
    private final AiFactoryProperties props;
    private final ProcessRunner runner;
    private final RepositoryContextService contextService;
    private final PromptService prompts;
    private final LlmGatewayClient llm;
    private final SandboxService sandbox;
    private final GiteaService gitea;
    private final Counter submittedTasks;
    private final Counter completedTasks;
    private final Counter failedTasks;

    public TaskService(AiFactoryProperties props, ProcessRunner runner, RepositoryContextService contextService,
                       PromptService prompts, LlmGatewayClient llm, SandboxService sandbox, GiteaService gitea,
                       MeterRegistry metrics) {
        this.props = props;
        this.runner = runner;
        this.contextService = contextService;
        this.prompts = prompts;
        this.llm = llm;
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
        String id = UUID.randomUUID().toString().substring(0, 8);
        String ticketNumber = nextTicketNumber();
        TaskState state = new TaskState(id, ticketNumber, request);
        tasks.put(id, state);
        submittedTasks.increment();
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
        if (state.request.isDryRun()) throw new IllegalStateException("A dry-run task cannot be approved. Re-submit it with dryRun=false.");
        state.transition(TaskStatus.APPROVED, "Human approval recorded");
        executor.submit(() -> {
            try {
                Path workspace = Path.of(state.workspace);
                String prUrl = gitea.commitPushAndCreatePr(workspace, state.request.repositoryUrl(), state.request.effectiveBranch(), state.id, state.request.requirement());
                state.pullRequestUrl = prUrl;
                state.transition(TaskStatus.PR_CREATED, "Pull request created: " + prUrl);
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

            s.transition(TaskStatus.CLONING, "Cloning repository");
            runner.run(List.of("git", "clone", "--depth", "1", "--branch", s.request.effectiveBranch(), s.request.repositoryUrl(), ws.toString()), null, Duration.ofMinutes(2));
            String context = contextService.collect(ws);

            s.transition(TaskStatus.PLANNING, "Planner agent analyzing requirement and repository context");
            s.plan = llm.chat(s.request.effectiveLlmMode(), prompts.load("planner"), "REQUIREMENT:\n" + s.request.requirement() + "\n\nREPOSITORY CONTEXT:\n" + context);
            Files.writeString(ws.resolve(".ai-plan.md"), s.plan);

            s.transition(TaskStatus.GENERATING_PATCH, "Developer agent generating a unified diff");
            String rawPatch = llm.chat(s.request.effectiveLlmMode(), prompts.load("developer"), "REQUIREMENT:\n" + s.request.requirement() + "\n\nPLAN:\n" + s.plan + "\n\nREPOSITORY CONTEXT:\n" + context);
            s.patch = validateAndRepairPatch(s, ws, rawPatch);

            if (!s.request.isDryRun()) {
                s.transition(TaskStatus.APPLYING_PATCH, "Applying generated patch inside isolated Docker sandbox");
                sandbox.applyPatch(ws);
            } else {
                s.transition(TaskStatus.APPLYING_PATCH, "Dry-run: patch generated but not applied");
            }

            if (!s.request.isDryRun()) {
                s.transition(TaskStatus.TESTING, "Running deterministic build and tests in sandbox");
                String deterministicTests = tail(sandbox.test(ws), 12000);
                String testerReview = llm.chat(s.request.effectiveLlmMode(), prompts.load("tester"),
                        "REQUIREMENT:\n" + s.request.requirement() + "\n\nPATCH:\n" + s.patch +
                                "\n\nDETERMINISTIC TEST EVIDENCE:\n" + deterministicTests);
                s.testSummary = deterministicTests + "\n\n--- AI TESTER REVIEW ---\n" + testerReview;
                Files.createDirectories(ws.resolve(".ai-factory"));
                Files.writeString(ws.resolve(".ai-factory/test.txt"), s.testSummary);

                s.transition(TaskStatus.QUALITY_SCANNING, "Running SonarQube quality analysis");
                s.qualitySummary = tail(sandbox.quality(ws), 12000);

                s.transition(TaskStatus.SECURITY_SCANNING, "Generating SBOM and running Trivy");
                s.securitySummary = tail(sandbox.security(ws), 12000);
            } else {
                s.transition(TaskStatus.TESTING, "Dry-run: deterministic tests skipped; Tester agent reviews the proposed patch");
                String testerReview = llm.chat(s.request.effectiveLlmMode(), prompts.load("tester"),
                        "REQUIREMENT:\n" + s.request.requirement() + "\n\nPATCH:\n" + s.patch +
                                "\n\nDETERMINISTIC TEST EVIDENCE:\nNot executed because dryRun=true");
                s.testSummary = "Deterministic execution skipped because dryRun=true.\n\n--- AI TESTER REVIEW ---\n" + testerReview;
                s.transition(TaskStatus.QUALITY_SCANNING, "Dry-run: SonarQube analysis skipped");
                s.qualitySummary = "Skipped because dryRun=true";
                s.transition(TaskStatus.SECURITY_SCANNING, "Dry-run: security scans skipped");
                s.securitySummary = "Skipped because dryRun=true";
            }

            s.transition(TaskStatus.REVIEWING, "Reviewer agent assessing plan, patch and deterministic evidence");
            s.review = llm.chat(s.request.effectiveLlmMode(), prompts.load("reviewer"),
                    "REQUIREMENT:\n" + s.request.requirement() + "\n\nPLAN:\n" + s.plan + "\n\nPATCH:\n" + s.patch +
                            "\n\nTEST EVIDENCE:\n" + s.testSummary + "\n\nQUALITY EVIDENCE:\n" + s.qualitySummary +
                            "\n\nSECURITY EVIDENCE:\n" + s.securitySummary);
            Files.writeString(ws.resolve(".ai-review.md"), s.review);

            s.transition(TaskStatus.WAITING_APPROVAL, "Pipeline complete. Human approval required before commit/push/PR.");
            completedTasks.increment();
        } catch (Exception e) {
            failedTasks.increment();
            s.fail(e);
        }
    }

    private String validateAndRepairPatch(TaskState state, Path workspace, String rawPatch) throws Exception {
        String patch = UnifiedDiffNormalizer.normalize(stripFence(rawPatch));
        Files.writeString(workspace.resolve("changes.patch"), patch);

        try {
            sandbox.checkPatch(workspace);
            return patch;
        } catch (Exception validationFailure) {
            Files.writeString(workspace.resolve("changes.invalid.patch"), patch);
            String repaired = llm.chat(state.request.effectiveLlmMode(), prompts.load("patch-repair"),
                    "REQUIREMENT:\n" + state.request.requirement() + "\n\nPLAN:\n" + state.plan +
                            "\n\nCURRENT FILE CONTENTS (authoritative):\n" + affectedFileContext(workspace, patch) +
                            "\n\nINVALID PATCH:\n" + patch + "\n\nGIT APPLY ERROR:\n" + validationFailure.getMessage());
            patch = UnifiedDiffNormalizer.normalize(stripFence(repaired));
            Files.writeString(workspace.resolve("changes.patch"), patch);
            sandbox.checkPatch(workspace);
            return patch;
        }
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

    String nextTicketNumber() {
        return "AF-%04d".formatted(ticketSequence.getAndIncrement());
    }
}
