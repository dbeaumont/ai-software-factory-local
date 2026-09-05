package com.example.aifactory.workflow.temporal;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.VersioningBehavior;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowVersioningBehavior;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit fixture generator; reference histories are never silently refreshed by the normal test suite. */
@EnabledIfSystemProperty(named = "temporal.history.generate", matches = "true")
class TemporalReferenceHistoryGeneratorTest {
    private static final String TASK_QUEUE = "reference-history-generator";

    @Test
    void generateVersionedReferenceHistories() throws IOException {
        Path output = Path.of(System.getProperty("temporal.history.output",
                "target/generated-test-resources/temporal-histories/v1"));
        Files.createDirectories(output);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, ReferenceDelegationWorkflow.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();

            generateSuccess(environment, output);
            generateFailure(environment, output);
            generateWaiting(environment, output);
            generateCancellation(environment, output);
            generateContinueAsNew(environment, output);
        }
    }

    private static void generateSuccess(TestWorkflowEnvironment environment, Path output) throws IOException {
        SoftwareFactoryWorkflow workflow = stub(environment, "history-success");
        assertThat(workflow.run(request("history-success"))).extracting(SoftwareFactoryWorkflow.Result::status)
                .isEqualTo("READY_FOR_DELEGATION");
        save(environment, "history-success", null, output.resolve("success.json"));
    }

    private static void generateFailure(TestWorkflowEnvironment environment, Path output) throws IOException {
        SoftwareFactoryWorkflow workflow = stub(environment, "history-failure");
        SoftwareFactoryWorkflow.Request failing = new SoftwareFactoryWorkflow.Request(
                "history-failure", "attempt-1", "a".repeat(40), "change",
                List.of(delegation("history-failure", "failed")));
        assertThat(workflow.run(failing).status()).isEqualTo("DELEGATIONS_BLOCKED");
        save(environment, "history-failure", null, output.resolve("failure.json"));
    }

    private static void generateWaiting(TestWorkflowEnvironment environment, Path output) throws IOException {
        SoftwareFactoryWorkflow workflow = stub(environment, "history-waiting");
        String manifest = "b".repeat(64);
        WorkflowClient.start(workflow::run, approvalRequest("history-waiting", manifest));
        awaitStatus(workflow, "WAITING_APPROVAL");
        save(environment, "history-waiting", null, output.resolve("waiting.json"));
    }

    private static void generateCancellation(TestWorkflowEnvironment environment, Path output) throws IOException {
        SoftwareFactoryWorkflow workflow = stub(environment, "history-cancellation");
        String manifest = "c".repeat(64);
        WorkflowClient.start(workflow::run, approvalRequest("history-cancellation", manifest));
        awaitStatus(workflow, "WAITING_APPROVAL");
        workflow.cancel(new SoftwareFactoryWorkflow.CancellationSignal(
                "history-cancellation", "attempt-1", "fixture cancellation", "fixture-generator",
                "2026-09-06T00:00:00Z"));
        assertThat(WorkflowStub.fromTyped(workflow).getResult(SoftwareFactoryWorkflow.Result.class).status())
                .isEqualTo("CANCELLED");
        save(environment, "history-cancellation", null, output.resolve("cancellation.json"));
    }

    private static void generateContinueAsNew(TestWorkflowEnvironment environment, Path output) throws IOException {
        SoftwareFactoryWorkflow workflow = stub(environment, "history-continue-as-new");
        List<DelegationWorkflow.Request> delegations = List.of(
                delegation("history-continue-as-new", "node-1"),
                delegation("history-continue-as-new", "node-2"));
        SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                "history-continue-as-new", "attempt-1", "a".repeat(40), "change", delegations,
                null, List.of(), new SoftwareFactoryWorkflow.ExecutionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, 1));
        WorkflowExecution initial = WorkflowClient.start(workflow::run, request);
        assertThat(WorkflowStub.fromTyped(workflow).getResult(SoftwareFactoryWorkflow.Result.class).status())
                .isEqualTo("DELEGATIONS_COMPLETED");
        save(environment, "history-continue-as-new", initial.getRunId(),
                output.resolve("continue-as-new.json"));
    }

    private static SoftwareFactoryWorkflow stub(TestWorkflowEnvironment environment, String taskId) {
        return environment.getWorkflowClient().newWorkflowStub(SoftwareFactoryWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(TemporalIds.workflow(taskId, "attempt-1"))
                        .setTaskQueue(TASK_QUEUE).build());
    }

    private static SoftwareFactoryWorkflow.Request request(String taskId) {
        return new SoftwareFactoryWorkflow.Request(taskId, "attempt-1", "a".repeat(40), "change");
    }

    private static SoftwareFactoryWorkflow.Request approvalRequest(String taskId, String manifest) {
        return new SoftwareFactoryWorkflow.Request(taskId, "attempt-1", "a".repeat(40), "change", List.of(),
                new SoftwareFactoryWorkflow.ApprovalRequest(
                        manifest, "evidence://" + taskId + "/manifest/" + manifest, "d".repeat(64)));
    }

    private static DelegationWorkflow.Request delegation(String taskId, String nodeId) {
        return new DelegationWorkflow.Request(taskId, "attempt-1", nodeId, "supervisor", "code-agent",
                "a".repeat(40), nodeId);
    }

    private static void awaitStatus(SoftwareFactoryWorkflow workflow, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(workflow.status())) return;
            Thread.onSpinWait();
        }
        assertThat(workflow.status()).isEqualTo(expected);
    }

    private static void save(TestWorkflowEnvironment environment, String taskId, String runId, Path destination)
            throws IOException {
        String workflowId = TemporalIds.workflow(taskId, "attempt-1");
        WorkflowExecutionHistory history = runId == null
                ? environment.getWorkflowClient().fetchHistory(workflowId)
                : environment.getWorkflowClient().fetchHistory(workflowId, runId);
        Files.writeString(destination, history.toJson(true));
    }

    public static final class ReferenceDelegationWorkflow implements DelegationWorkflow {
        @Override
        @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
        public Result run(Request request) {
            if ("failed".equals(request.nodeId())) {
                throw ApplicationFailure.newNonRetryableFailure("reference failure", "REFERENCE_FAILURE");
            }
            return new Result(request.nodeId(), request.role(), "READY_FOR_ACTIVITIES");
        }
    }
}
