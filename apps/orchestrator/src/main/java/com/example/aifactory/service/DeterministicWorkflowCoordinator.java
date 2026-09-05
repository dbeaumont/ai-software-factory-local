package com.example.aifactory.service;

import com.example.aifactory.config.AgentToolingProperties;
import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.workflow.WorkflowCoordinator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Temporary local oracle for the historical pipeline.
 *
 * <p>This class owns ordering only. All business effects live in {@link PipelineStepService}, which has no
 * executor or scheduler and can therefore be called by Temporal activities during the cutover.</p>
 */
@Service
public class DeterministicWorkflowCoordinator implements WorkflowCoordinator {
    private static final Logger log = LoggerFactory.getLogger(DeterministicWorkflowCoordinator.class);

    private final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    private final PipelineStepService steps;
    private final Counter completedTasks;
    private final Counter failedTasks;
    private final AsyncTaskTracer taskTracer;

    @Autowired
    public DeterministicWorkflowCoordinator(PipelineStepService steps, MeterRegistry metrics, AsyncTaskTracer taskTracer) {
        this.steps = steps;
        this.taskTracer = taskTracer;
        this.completedTasks = Counter.builder("ai_factory_tasks_completed")
                .description("Tasks that completed validation").register(metrics);
        this.failedTasks = Counter.builder("ai_factory_tasks_failed")
                .description("Tasks that failed before approval").register(metrics);
    }

    /** Test-only compatibility constructor used by the frozen local pipeline oracle. */
    DeterministicWorkflowCoordinator(AiFactoryProperties props, ProcessRunner runner,
                                     RepositoryContextProvider contextService, PromptService prompts,
                                     LlmGatewayClient llm, AgentResponseValidator agentResponses,
                                     SandboxExecutor sandbox, PatchIntegrator patchIntegrator,
                                     AssuranceGateway assurance, ScmDeliveryGateway scmDelivery,
                                     MeterRegistry metrics, ObjectMapper objectMapper,
                                     AgentToolingProperties agentTooling, AgentContextToolHost agentTools) {
        this(new PipelineStepService(props, runner, contextService, prompts, llm, agentResponses, sandbox,
                patchIntegrator, assurance, scmDelivery, metrics, objectMapper, agentTooling, agentTools),
                metrics, AsyncTaskTracer.noop());
    }

    @Override
    public void start(TaskState state) {
        taskTracer.submit(executor, state, "execute", () -> runPipeline(state));
    }

    @Override
    public void resumeAfterApproval(TaskState state) {
        taskTracer.submit(executor, state, "resume-after-approval", () -> {
            try {
                steps.deliver(state);
                state.transition(TaskStatus.PR_CREATED, "Pull request created: " + state.pullRequestUrl);
                log.info("Task {} ({}) created pull request", state.id, state.ticketNumber);
            } catch (Exception exception) {
                state.fail(exception);
            }
        });
    }

    private void runPipeline(TaskState state) {
        try {
            Path workspace = steps.initializeWorkspace(state);
            state.transition(TaskStatus.CLONING, "Cloning repository");
            steps.cloneSource(state, workspace);
            state.transition(TaskStatus.PLANNING, "Planner agent analyzing requirement and repository context");
            steps.plan(state, workspace);
            state.transition(TaskStatus.GENERATING_PATCH, "Developer agent generating a unified diff");
            steps.generateAndRepairPatch(state, workspace);
            state.transition(TaskStatus.APPLYING_PATCH, "Applying generated patch inside isolated Docker sandbox");
            steps.applyPatch(state, workspace);
            state.transition(TaskStatus.TESTING, "Running deterministic build and tests in sandbox");
            steps.test(state, workspace);
            state.transition(TaskStatus.QUALITY_SCANNING, "Running SonarQube quality analysis");
            steps.quality(state, workspace);
            state.transition(TaskStatus.SECURITY_SCANNING, "Generating SBOM and running Trivy");
            steps.security(state, workspace);
            state.transition(TaskStatus.REVIEWING, "Reviewer agent assessing plan, patch and deterministic evidence");
            steps.review(state, workspace);
            steps.prepareDelivery(state);
            state.transition(TaskStatus.WAITING_APPROVAL,
                    "Pipeline complete. Human approval required before commit/push/PR.");
            completedTasks.increment();
            log.info("Task {} ({}) completed all automated stages and awaits approval", state.id, state.ticketNumber);
        } catch (Exception exception) {
            failedTasks.increment();
            state.fail(exception);
        }
    }

    static String stripFence(String value) {
        return PipelineStepService.stripFence(value);
    }

    static <T> T withSingleContractRetry(Supplier<T> invocation, Supplier<T> retryInvocation,
                                         Predicate<T> contract,
                                         java.util.function.Consumer<String> retryObserver) {
        return PipelineStepService.withSingleContractRetry(invocation, retryInvocation, contract, retryObserver);
    }

    static String withSingleRetryableCompletion(Supplier<String> invocation, Supplier<String> retryInvocation,
                                                java.util.function.Consumer<String> retryObserver) {
        return PipelineStepService.withSingleRetryableCompletion(invocation, retryInvocation, retryObserver);
    }

    static int retryMaxTokensFor(String promptName) {
        return PipelineStepService.retryMaxTokensFor(promptName);
    }

    static int maxTokensFor(String promptName) {
        return PipelineStepService.maxTokensFor(promptName);
    }

    static String untrusted(String label, String content) {
        return PipelineStepService.untrusted(label, content);
    }
}
