package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.PatchIntegrationPlanner;
import com.example.aifactory.service.PatchAttemptPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PatchIntegrationWorkflowTest {
    @Test
    void workflowAloneSelectsTheFixedSandboxProfiles() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("patch-integration-test");
            AtomicReference<PatchIntegrationActivities.Request> captured = new AtomicReference<>();
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
                            new PatchAttemptPolicy().initial(), planDigest)));

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
        }
    }

    private static PatchIntegrationActivities.PatchArtifact artifact(
            String nodeId, String proposalId, String digest) {
        return new PatchIntegrationActivities.PatchArtifact(nodeId, proposalId,
                "evidence://task-1/attempt-1/code-patch/" + digest, digest);
    }
}
