package com.example.aifactory.workflow.temporal;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.WorkflowVersioningBehavior;
import io.temporal.common.VersioningBehavior;
import io.temporal.worker.Worker;
import com.example.aifactory.service.IndependentReviewBundle;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareFactoryWorkflowTest {
    @Test
    void executesAsATemporalRootWorkflow() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(TemporalIds.workflow("task-1", "attempt-1"))
                            .setTaskQueue("software-factory-test").build());

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryWorkflow.Request(
                    "task-1", "attempt-1", "a".repeat(40), "change"));

            assertThat(result.status()).isEqualTo("READY_FOR_DELEGATION");
            assertThat(result.chronology()).containsExactly("WORKFLOW_STARTED");
            assertThat(result.delegations()).isEmpty();
            assertThat(result.approvedManifestId()).isNull();
            assertThat(result.humanDecisions()).isEmpty();
            assertThat(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
                    .isEqualTo(TemporalIds.workflow("task-1", "attempt-1"));
        }
    }

    @Test
    void executesAGenericDelegationAsAChildWorkflow() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(TemporalIds.workflow("task-2", "attempt-1"))
                            .setTaskQueue("software-factory-test").build());
            DelegationWorkflow.Request child = new DelegationWorkflow.Request(
                    "task-2", "attempt-1", "code-1", "supervisor", "code-agent",
                    "a".repeat(40), "produce a patch proposal",
                    new DelegationWorkflow.Budget(2_000, 500_000, 4));

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryWorkflow.Request(
                    "task-2", "attempt-1", "a".repeat(40), "change", java.util.List.of(child)));

            assertThat(result.status()).isEqualTo("DELEGATIONS_COMPLETED");
            assertThat(result.delegations()).containsExactly(
                    new DelegationWorkflow.Result("code-1", "code-agent", "READY_FOR_ACTIVITIES"));
            assertThat(result.chronology()).containsExactly("WORKFLOW_STARTED", "DELEGATION_COMPLETED:code-1");
        }
    }

    @Test
    void resumesOnlyForAnApprovalBoundToTheSubmittedManifest() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(TemporalIds.workflow("task-3", "attempt-1"))
                            .setTaskQueue("software-factory-test").build());
            String manifestId = "b".repeat(64);
            String digest = "c".repeat(64);
            DelegationWorkflow.Request approvalChild = new DelegationWorkflow.Request(
                    "task-3", "attempt-1", "security-1", "supervisor", "security-agent",
                    "a".repeat(40), "assess security", new DelegationWorkflow.Budget(3_000, 750_000, 5));
            SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                    "task-3", "attempt-1", "a".repeat(40), "change", java.util.List.of(approvalChild),
                    new SoftwareFactoryWorkflow.ApprovalRequest(
                            manifestId, "evidence://task-3/attempt-1/manifest/" + manifestId, digest));
            io.temporal.client.WorkflowClient.start(workflow::run, request);

            awaitStatus(workflow, "WAITING_APPROVAL");
            assertThat(workflow.dag()).containsExactly(new SoftwareFactoryWorkflow.DelegationView(
                    "security-1", "supervisor", "security-agent", "READY_FOR_ACTIVITIES"));
            assertThat(workflow.budgets()).containsEntry(
                    "security-1", new DelegationWorkflow.Budget(3_000, 750_000, 5));
            assertThat(workflow.evidence()).containsExactly(
                    "evidence://task-3/attempt-1/manifest/" + manifestId);
            assertThat(workflow.pendingEffects()).containsExactly(
                    new SoftwareFactoryWorkflow.PendingEffectView("APPROVAL", manifestId));

            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-3", "attempt-1", "d".repeat(64), digest,
                    "APPROVE", "wrong", "2026-09-02T10:00:00Z"));
            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-3", "attempt-1", manifestId, digest,
                    "APPROVE", "reviewer@example.test", "2026-09-02T10:01:00Z"));
            SoftwareFactoryWorkflow.Result result = WorkflowStub.fromTyped(workflow)
                    .getResult(SoftwareFactoryWorkflow.Result.class);

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(result.approvedManifestId()).isEqualTo(manifestId);
            assertThat(result.approvedBy()).isEqualTo("reviewer@example.test");
            assertThat(result.chronology()).containsExactly(
                    "WORKFLOW_STARTED", "DELEGATION_COMPLETED:security-1",
                    "WAITING_APPROVAL:" + manifestId, "APPROVED:" + manifestId);
        }
    }

    @Test
    void recordsComplementaryHumanDecisionsAndSupportsCancellation() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow decisions = stub(environment, "task-4");
            SoftwareFactoryWorkflow.HumanDecisionRequest decisionRequest =
                    new SoftwareFactoryWorkflow.HumanDecisionRequest("risk-acceptance", "Accept R3?",
                            java.util.Set.of("ACCEPT", "REJECT"), java.util.List.of("evidence://task-4/risk"));
            io.temporal.client.WorkflowClient.start(decisions::run, new SoftwareFactoryWorkflow.Request(
                    "task-4", "attempt-1", "a".repeat(40), "change", java.util.List.of(), null,
                    java.util.List.of(decisionRequest)));
            decisions.decide(new SoftwareFactoryWorkflow.HumanDecisionSignal(
                    "task-4", "attempt-1", "risk-acceptance", "ACCEPT", "security-reviewer",
                    "2026-09-02T11:00:00Z"));
            SoftwareFactoryWorkflow.Result decisionResult = WorkflowStub.fromTyped(decisions)
                    .getResult(SoftwareFactoryWorkflow.Result.class);
            assertThat(decisionResult.status()).isEqualTo("DECISIONS_COMPLETED");
            assertThat(decisionResult.humanDecisions()).containsEntry("risk-acceptance", "ACCEPT");

            SoftwareFactoryWorkflow cancelled = stub(environment, "task-5");
            String manifest = "e".repeat(64);
            io.temporal.client.WorkflowClient.start(cancelled::run, new SoftwareFactoryWorkflow.Request(
                    "task-5", "attempt-1", "a".repeat(40), "change", java.util.List.of(),
                    new SoftwareFactoryWorkflow.ApprovalRequest(
                            manifest, "evidence://task-5/attempt-1/manifest/" + manifest, "f".repeat(64))));
            cancelled.cancel(new SoftwareFactoryWorkflow.CancellationSignal(
                    "task-5", "attempt-1", "request withdrawn", "requester", "2026-09-02T11:01:00Z"));
            SoftwareFactoryWorkflow.Result cancelledResult = WorkflowStub.fromTyped(cancelled)
                    .getResult(SoftwareFactoryWorkflow.Result.class);
            assertThat(cancelledResult.status()).isEqualTo("CANCELLED");
            assertThat(cancelledResult.cancellationReason()).isEqualTo("request withdrawn");
        }
    }

    @Test
    void boundsEachRunAndCarriesStateAcrossContinueAsNew() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = stub(environment, "task-6");
            java.util.List<DelegationWorkflow.Request> delegations = java.util.stream.IntStream.rangeClosed(1, 3)
                    .mapToObj(index -> new DelegationWorkflow.Request(
                            "task-6", "attempt-1", "node-" + index, "supervisor", "code-agent",
                            "a".repeat(40), "delegation " + index))
                    .toList();
            SoftwareFactoryWorkflow.ExecutionPolicy policy = new SoftwareFactoryWorkflow.ExecutionPolicy(
                    Long.MAX_VALUE, Long.MAX_VALUE, 1);

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryWorkflow.Request(
                    "task-6", "attempt-1", "a".repeat(40), "change", delegations, null,
                    java.util.List.of(), policy));

            assertThat(result.status()).isEqualTo("DELEGATIONS_COMPLETED");
            assertThat(result.delegations()).extracting(DelegationWorkflow.Result::nodeId)
                    .containsExactly("node-1", "node-2", "node-3");
            assertThat(result.chronology()).containsExactly(
                    "WORKFLOW_STARTED", "DELEGATION_COMPLETED:node-1", "CONTINUED_AS_NEW:1",
                    "DELEGATION_COMPLETED:node-2", "CONTINUED_AS_NEW:2",
                    "DELEGATION_COMPLETED:node-3");
        }
    }

    @Test
    void resumesAnApprovalAfterSeveralVirtualDays() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = stub(environment, "task-7");
            String manifest = "7".repeat(64);
            SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                    "task-7", "attempt-1", "a".repeat(40), "change", java.util.List.of(),
                    new SoftwareFactoryWorkflow.ApprovalRequest(
                            manifest, "evidence://task-7/attempt-1/manifest/" + manifest, "8".repeat(64)));
            io.temporal.client.WorkflowClient.start(workflow::run, request);
            awaitStatus(workflow, "WAITING_APPROVAL");
            long before = environment.currentTimeMillis();

            environment.sleep(Duration.ofDays(7));

            assertThat(environment.currentTimeMillis() - before).isGreaterThanOrEqualTo(Duration.ofDays(7).toMillis());
            assertThat(workflow.status()).isEqualTo("WAITING_APPROVAL");
            assertThat(workflow.pendingEffects()).containsExactly(
                    new SoftwareFactoryWorkflow.PendingEffectView("APPROVAL", manifest));
            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-7", "attempt-1", manifest, "8".repeat(64), "APPROVE",
                    "reviewer@example.test", "2026-09-09T10:00:00Z"));

            assertThat(WorkflowStub.fromTyped(workflow).getResult(SoftwareFactoryWorkflow.Result.class).status())
                    .isEqualTo("APPROVED");
        }
    }

    @Test
    void launchesIndependentReviewerFromRootAndRejectsItAsASupervisorDelegation() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = stub(environment, "task-8");
            IndependentReviewWorkflow.Request review = new IndependentReviewWorkflow.Request(
                    "task-8", "attempt-1", "final-review", "a".repeat(40), reviewBundle("task-8"), null);
            SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                    "task-8", "attempt-1", "sample-repo", "a".repeat(40), "change",
                    java.util.List.of(), null, java.util.List.of(), null, null, review);

            SoftwareFactoryWorkflow.Result result = workflow.run(request);

            assertThat(result.independentReview()).isEqualTo(new IndependentReviewWorkflow.Result(
                    "final-review", "independent-reviewer", "READY_FOR_ACTIVITIES"));
            assertThat(result.chronology()).containsExactly(
                    "WORKFLOW_STARTED", "INDEPENDENT_REVIEW_COMPLETED:final-review");
            assertThat(workflow.dag()).containsExactly(new SoftwareFactoryWorkflow.DelegationView(
                    "final-review", "workflow", "independent-reviewer", "READY_FOR_ACTIVITIES"));

            DelegationWorkflow.Request supervisorChild = new DelegationWorkflow.Request(
                    "task-9", "attempt-1", "invalid-review", "supervisor", "independent-reviewer",
                    "a".repeat(40), "review");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SoftwareFactoryWorkflowImpl().run(
                    new SoftwareFactoryWorkflow.Request("task-9", "attempt-1", "a".repeat(40),
                            "change", java.util.List.of(supervisorChild))))
                    .hasMessageContaining("workflow request is invalid");
        }
    }

    @Test
    void propagatesAChildFailureToItsDependentWithoutLaunchingIt() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("failure-propagation-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, FailingDelegationWorkflow.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(TemporalIds.workflow("task-10", "attempt-1"))
                            .setTaskQueue("failure-propagation-test").build());
            DelegationWorkflow.Request failed = new DelegationWorkflow.Request(
                    "task-10", "attempt-1", "failed", "supervisor", "code-agent",
                    "a".repeat(40), "fail deterministically");
            DelegationWorkflow.Request dependent = new DelegationWorkflow.Request(
                    "task-10", "attempt-1", "dependent", "failed", "test-design-agent",
                    "a".repeat(40), "must not run");

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryWorkflow.Request(
                    "task-10", "attempt-1", "a".repeat(40), "change",
                    java.util.List.of(dependent, failed)));

            assertThat(result.status()).isEqualTo("DELEGATIONS_BLOCKED");
            assertThat(result.delegations()).containsExactly(
                    new DelegationWorkflow.Result("failed", "code-agent", "FAILED"),
                    new DelegationWorkflow.Result("dependent", "test-design-agent", "BLOCKED_BY_FAILED"));
            assertThat(result.chronology()).containsExactly(
                    "WORKFLOW_STARTED", "DELEGATION_FAILED:failed:FAILED",
                    "DELEGATION_BLOCKED:dependent:BLOCKED_BY_FAILED");
        }
    }

    public static final class FailingDelegationWorkflow implements DelegationWorkflow {
        @Override
        @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
        public Result run(Request request) {
            if ("failed".equals(request.nodeId())) {
                throw ApplicationFailure.newNonRetryableFailure("expected failure", "TEST_FAILURE");
            }
            return new Result(request.nodeId(), request.role(), "SHOULD_NOT_RUN");
        }
    }

    private static IndependentReviewBundle reviewBundle(String taskId) {
        return new IndependentReviewBundle(taskId, "attempt-1", "a".repeat(40),
                new IndependentReviewBundle.ConsolidatedPatch("patch-1", "evidence://" + taskId + "/patch",
                        "b".repeat(64), java.util.List.of("src/App.java")),
                new IndependentReviewBundle.FinalManifest("c".repeat(64),
                        "evidence://" + taskId + "/manifest", "d".repeat(64)),
                java.util.List.of(new IndependentReviewBundle.ResultReference("result-1", "code-agent",
                        "evidence://" + taskId + "/result", "e".repeat(64))),
                java.util.List.of(new IndependentReviewBundle.ContradictionReference(
                        "contradiction-1", "OPEN", "evidence://" + taskId + "/contradiction",
                        "f".repeat(64))));
    }

    private static SoftwareFactoryWorkflow stub(TestWorkflowEnvironment environment, String taskId) {
        return environment.getWorkflowClient().newWorkflowStub(SoftwareFactoryWorkflow.class,
                WorkflowOptions.newBuilder().setWorkflowId(TemporalIds.workflow(taskId, "attempt-1"))
                        .setTaskQueue("software-factory-test").build());
    }

    private static void awaitStatus(SoftwareFactoryWorkflow workflow, String expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String observed;
        do {
            observed = workflow.status();
            if (expected.equals(observed)) return;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        assertThat(observed).isEqualTo(expected);
    }
}
