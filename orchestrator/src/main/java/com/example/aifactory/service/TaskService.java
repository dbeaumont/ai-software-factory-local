package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.*;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class TaskService {
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final AiFactoryProperties props;
    private final ProcessRunner runner;
    private final RepositoryContextService contextService;
    private final PromptService prompts;
    private final LlmGatewayClient llm;
    private final SandboxService sandbox;
    private final GiteaService gitea;

    public TaskService(AiFactoryProperties props, ProcessRunner runner, RepositoryContextService contextService,
                       PromptService prompts, LlmGatewayClient llm, SandboxService sandbox, GiteaService gitea) {
        this.props = props;
        this.runner = runner;
        this.contextService = contextService;
        this.prompts = prompts;
        this.llm = llm;
        this.sandbox = sandbox;
        this.gitea = gitea;
    }

    public TaskView create(TaskRequest request) {
        if (request.repositoryUrl() == null || request.repositoryUrl().isBlank()) throw new IllegalArgumentException("repositoryUrl is required");
        if (request.requirement() == null || request.requirement().isBlank()) throw new IllegalArgumentException("requirement is required");
        if (request.effectiveLlmMode() == LlmMode.CLOUD && !props.cloudEnabled()) {
            throw new IllegalArgumentException("Cloud LLM is disabled by configuration");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        TaskState state = new TaskState(id, request);
        tasks.put(id, state);
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
            s.patch = stripFence(rawPatch);
            Files.writeString(ws.resolve("changes.patch"), s.patch);

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

                s.transition(TaskStatus.SECURITY_SCANNING, "Generating SBOM and running Trivy");
                s.securitySummary = tail(sandbox.security(ws), 12000);
            } else {
                s.transition(TaskStatus.TESTING, "Dry-run: deterministic tests skipped; Tester agent reviews the proposed patch");
                String testerReview = llm.chat(s.request.effectiveLlmMode(), prompts.load("tester"),
                        "REQUIREMENT:\n" + s.request.requirement() + "\n\nPATCH:\n" + s.patch +
                                "\n\nDETERMINISTIC TEST EVIDENCE:\nNot executed because dryRun=true");
                s.testSummary = "Deterministic execution skipped because dryRun=true.\n\n--- AI TESTER REVIEW ---\n" + testerReview;
                s.transition(TaskStatus.SECURITY_SCANNING, "Dry-run: security scans skipped");
                s.securitySummary = "Skipped because dryRun=true";
            }

            s.transition(TaskStatus.REVIEWING, "Reviewer agent assessing plan, patch and deterministic evidence");
            s.review = llm.chat(s.request.effectiveLlmMode(), prompts.load("reviewer"),
                    "REQUIREMENT:\n" + s.request.requirement() + "\n\nPLAN:\n" + s.plan + "\n\nPATCH:\n" + s.patch +
                            "\n\nTEST EVIDENCE:\n" + s.testSummary + "\n\nSECURITY EVIDENCE:\n" + s.securitySummary);
            Files.writeString(ws.resolve(".ai-review.md"), s.review);

            s.transition(TaskStatus.WAITING_APPROVAL, "Pipeline complete. Human approval required before commit/push/PR.");
        } catch (Exception e) {
            s.fail(e);
        }
    }

    private static String stripFence(String s) {
        String out = s.strip();
        if (out.startsWith("```")) {
            int firstNewline = out.indexOf('\n');
            if (firstNewline >= 0) out = out.substring(firstNewline + 1);
            if (out.endsWith("```")) out = out.substring(0, out.length() - 3);
        }
        return out.strip();
    }

    private static String tail(String s, int max) {
        return s.length() <= max ? s : "...[truncated]...\n" + s.substring(s.length() - max);
    }
}
