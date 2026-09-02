package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.PatchIntegrationPlanner;
import com.example.aifactory.service.PatchAttemptPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchIntegrationWorkflowTest {
    @Test
    void workflowAloneSelectsTheFixedSandboxProfiles() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("patch-integration-test");
            AtomicReference<PatchIntegrationActivities.Request> captured = new AtomicReference<>();
            AtomicReference<PatchIntegrationActivities.CleanupRequest> cleanup = new AtomicReference<>();
            List<PatchIntegrationActivities.VerificationRequest> verificationCalls = new ArrayList<>();
            worker.registerWorkflowImplementationTypes(PatchIntegrationWorkflowImpl.class);
            worker.registerActivitiesImplementations(new PatchIntegrationActivities() {
                @Override public ApplicationResult apply(PatchIntegrationActivities.Request request) {
                    captured.set(request);
                    return new ApplicationResult(request.planDigest(), "d".repeat(64),
                            request.validationProfile(), request.applicationProfile(), "e".repeat(64), "APPLIED");
                }

                @Override public VerificationResult verify(VerificationRequest request) {
                    verificationCalls.add(request);
                    return new VerificationResult(request.kind(), "f".repeat(64), "PASSED");
                }

                @Override public CleanupResult cleanup(CleanupRequest request) {
                    cleanup.set(request);
                    return new CleanupResult(request.outcome(), request.plan().worktrees().size(),
                            false, false, "CLEANED");
                }
            });
            environment.start();

            PatchIntegrationActivities.PatchArtifact artifact = artifact("node-1", "proposal-1", "b".repeat(64));
            String planDigest = PatchIntegrationPlanner.digestIdentities(List.of(
                    new PatchIntegrationPlanner.PatchIdentity("node-1", "proposal-1", artifact.digest())));
            PatchIntegrationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    PatchIntegrationWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("patch-integration-task-1-attempt-1")
                            .setTaskQueue("patch-integration-test").build());

            PatchIntegrationWorkflow.Result result = workflow.run(new PatchIntegrationWorkflow.Request(
                    "task-1", "attempt-1", "a".repeat(40), "/tmp/integration", planDigest, List.of(artifact),
                    new PatchAttemptPolicy().authorizeIntegration(
                            new PatchAttemptPolicy().initial(), planDigest), cleanupPlan()));

            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(result.verifications()).extracting(PatchIntegrationActivities.VerificationResult::kind)
                    .containsExactly(PatchIntegrationActivities.VerificationKind.TESTS,
                            PatchIntegrationActivities.VerificationKind.QUALITY,
                            PatchIntegrationActivities.VerificationKind.SECURITY);
            assertThat(verificationCalls).extracting(PatchIntegrationActivities.VerificationRequest::kind)
                    .containsExactly(PatchIntegrationActivities.VerificationKind.TESTS,
                            PatchIntegrationActivities.VerificationKind.QUALITY,
                            PatchIntegrationActivities.VerificationKind.SECURITY);
            assertThat(captured.get().validationProfile()).isEqualTo("patch-check-v1");
            assertThat(captured.get().applicationProfile()).isEqualTo("patch-apply-v1");
            assertThat(captured.get().metadata().idempotencyKey())
                    .isEqualTo("effect-activity-task-1-attempt-1-integration-apply-patches-1");
            assertThat(cleanup.get().outcome()).isEqualTo(PatchIntegrationActivities.TerminalOutcome.SUCCESS);
        }
    }

    @Test
    void classifiesEveryTerminalFailureForTheDetachedCleanup() {
        assertThat(PatchIntegrationWorkflowImpl.terminalOutcome(new IllegalStateException("failed")))
                .isEqualTo(PatchIntegrationActivities.TerminalOutcome.FAILED);
        assertThat(PatchIntegrationWorkflowImpl.terminalOutcome(new RuntimeException(
                new TimeoutFailure("timeout", null, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE))))
                .isEqualTo(PatchIntegrationActivities.TerminalOutcome.TIMED_OUT);
        assertThat(PatchIntegrationWorkflowImpl.terminalOutcome(new RuntimeException(
                new CanceledFailure("cancelled"))))
                .isEqualTo(PatchIntegrationActivities.TerminalOutcome.CANCELLED);
    }

    @Test
    void detachedCleanupRunsWhenPatchApplicationFails() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("patch-integration-failure-test");
            AtomicReference<PatchIntegrationActivities.CleanupRequest> cleanup = new AtomicReference<>();
            worker.registerWorkflowImplementationTypes(PatchIntegrationWorkflowImpl.class);
            worker.registerActivitiesImplementations(new PatchIntegrationActivities() {
                @Override public ApplicationResult apply(PatchIntegrationActivities.Request request) {
                    throw ApplicationFailure.newNonRetryableFailure("invalid patch", "POLICY_DENIED");
                }

                @Override public VerificationResult verify(VerificationRequest request) {
                    throw new AssertionError("verification must not run");
                }

                @Override public CleanupResult cleanup(CleanupRequest request) {
                    cleanup.set(request);
                    return new CleanupResult(request.outcome(), 1, true, true, "CLEANED");
                }
            });
            environment.start();
            PatchIntegrationActivities.PatchArtifact artifact = artifact(
                    "node-1", "proposal-1", "b".repeat(64));
            String planDigest = PatchIntegrationPlanner.digestIdentities(List.of(
                    new PatchIntegrationPlanner.PatchIdentity("node-1", "proposal-1", artifact.digest())));
            PatchAttemptPolicy policy = new PatchAttemptPolicy();
            PatchIntegrationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    PatchIntegrationWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("patch-integration-failure-task-1-attempt-1")
                            .setTaskQueue("patch-integration-failure-test").build());

            assertThatThrownBy(() -> workflow.run(new PatchIntegrationWorkflow.Request(
                    "task-1", "attempt-1", "a".repeat(40), "/tmp/integration", planDigest,
                    List.of(artifact), policy.authorizeIntegration(policy.initial(), planDigest), cleanupPlan())))
                    .hasMessageContaining("PatchIntegrationWorkflow");
            assertThat(cleanup.get().outcome()).isEqualTo(PatchIntegrationActivities.TerminalOutcome.FAILED);
        }
    }

    private static PatchIntegrationActivities.CleanupPlan cleanupPlan() {
        return new PatchIntegrationActivities.CleanupPlan("/tmp/source", "/tmp/worktrees", "/tmp/integration",
                List.of(new PatchIntegrationActivities.WorktreeRef("worktree-node-1-0000000000000000",
                        "task-1", "attempt-1", "node-1", "a".repeat(40), "/tmp/worktrees/node-1")));
    }

    private static PatchIntegrationActivities.PatchArtifact artifact(
            String nodeId, String proposalId, String digest) {
        return new PatchIntegrationActivities.PatchArtifact(nodeId, proposalId,
                "evidence://task-1/attempt-1/code-patch/" + digest, digest);
    }
}
