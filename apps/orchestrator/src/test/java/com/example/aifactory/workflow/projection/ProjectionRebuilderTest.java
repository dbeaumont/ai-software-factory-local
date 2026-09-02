package com.example.aifactory.workflow.projection;

import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.service.IndependentReviewBundle;
import com.example.aifactory.workflow.temporal.DelegationWorkflow;
import com.example.aifactory.workflow.temporal.DelegationWorkflowImpl;
import com.example.aifactory.workflow.temporal.IndependentReviewWorkflow;
import com.example.aifactory.workflow.temporal.IndependentReviewWorkflowImpl;
import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflowImpl;
import com.example.aifactory.workflow.temporal.TemporalIds;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectionRebuilderTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String MANIFEST_ID = "b".repeat(64);
    private static final String MANIFEST_DIGEST = "c".repeat(64);
    private static final String MANIFEST_URI =
            "evidence://task-rebuild/attempt-1/manifest/" + MANIFEST_ID;

    @Test
    void reconstructsADeletedProjectionFromTemporalHistoryAndVerifiedEvidence() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("projection-rebuild-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            String workflowId = TemporalIds.workflow("task-rebuild", "attempt-1");
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId).setTaskQueue("projection-rebuild-test").build());
            DelegationWorkflow.Request delegation = new DelegationWorkflow.Request(
                    "task-rebuild", "attempt-1", "security-1", "supervisor", "security-agent",
                    COMMIT, "verify the change", new DelegationWorkflow.Budget(2_000, 300_000, 4));
            SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                    "task-rebuild", "attempt-1", "sample-repository", COMMIT, "secure the endpoint",
                    List.of(delegation), new SoftwareFactoryWorkflow.ApprovalRequest(
                    MANIFEST_ID, MANIFEST_URI, MANIFEST_DIGEST), List.of(), null, null, review());

            WorkflowExecution execution = WorkflowClient.start(workflow::run, request);
            awaitStatus(workflow, "WAITING_APPROVAL");
            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-rebuild", "attempt-1", MANIFEST_ID, MANIFEST_DIGEST, "APPROVE",
                    "reviewer@example.test", "2026-09-02T12:00:00Z"));
            assertThat(WorkflowStub.fromTyped(workflow).getResult(SoftwareFactoryWorkflow.Result.class).status())
                    .isEqualTo("APPROVED");

            RecordingProjectionStore store = new RecordingProjectionStore();
            ProjectionRebuilder rebuilder = new ProjectionRebuilder(
                    new TemporalProjectionHistorySource(environment.getWorkflowClient()),
                    evidence(MANIFEST_DIGEST), store);
            UiProjectionSnapshot original = rebuilder.rebuild(workflowId, execution.getRunId());
            store.clear(); // controlled loss of the PostgreSQL read model

            UiProjectionSnapshot restored = rebuilder.rebuild(workflowId, execution.getRunId());

            assertThat(restored).isEqualTo(original);
            assertThat(store.snapshot).isEqualTo(original);
            assertThat(restored.task().repositoryId()).isEqualTo("sample-repository");
            assertThat(restored.workflowRun().status()).isEqualTo("APPROVED");
            assertThat(restored.delegations()).filteredOn(projected -> "security-agent".equals(projected.role()))
                    .singleElement().satisfies(projected -> {
                assertThat(projected.delegationId()).isEqualTo("security-1");
                assertThat(projected.status()).isEqualTo("READY_FOR_ACTIVITIES");
                assertThat(projected.budgetTokens()).isEqualTo(2_000);
            });
            assertThat(restored.evidence()).singleElement().satisfies(projected -> {
                assertThat(projected.uri()).isEqualTo(MANIFEST_URI);
                assertThat(projected.digest()).isEqualTo(MANIFEST_DIGEST);
                assertThat(projected.status()).isEqualTo("COMPLETE");
            });
        }
    }

    @Test
    void leavesTheProjectionUntouchedWhenEvidenceNoLongerMatchesHistory() {
        SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                "task-rebuild", "attempt-1", "sample-repository", COMMIT, "secure the endpoint",
                List.of(), new SoftwareFactoryWorkflow.ApprovalRequest(
                MANIFEST_ID, MANIFEST_URI, MANIFEST_DIGEST), List.of(), null, null);
        ProjectionHistorySource source = (workflowId, runId) -> new ProjectionHistorySource.History(
                workflowId, runId, Instant.parse("2026-09-02T10:00:00Z"),
                Instant.parse("2026-09-02T10:01:00Z"), "APPROVED", request,
                new SoftwareFactoryWorkflow.Result("task-rebuild", "attempt-1", COMMIT, "APPROVED",
                        List.of("WORKFLOW_STARTED", "APPROVED:" + MANIFEST_ID), List.of(),
                        java.util.Map.of(), MANIFEST_ID, "reviewer", null));
        RecordingProjectionStore store = new RecordingProjectionStore();

        assertThatThrownBy(() -> new ProjectionRebuilder(source, evidence("d".repeat(64)), store)
                .rebuild("workflow-1", "run-1"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("diverges");
        assertThat(store.snapshot).isNull();
        assertThat(store.replacements).isZero();
    }

    private static EvidenceRepository evidence(String digest) {
        return new EvidenceRepository() {
            @Override public StoredEvidence store(StoreRequest request) { throw new UnsupportedOperationException(); }
            @Override public StoredManifest createManifest(ManifestRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public EvidenceSummary getSummary(String taskId, String attemptId, String uri, String actor) {
                assertThat(taskId).isEqualTo("task-rebuild");
                assertThat(attemptId).isEqualTo("attempt-1");
                assertThat(actor).isEqualTo("workflow");
                return new EvidenceSummary(uri, "manifest", digest, "COMPLETE", "CONFIDENTIAL", 512);
            }
            @Override public RawEvidence read(ReadRequest request) { throw new UnsupportedOperationException(); }
        };
    }

    private static IndependentReviewWorkflow.Request review() {
        IndependentReviewBundle bundle = new IndependentReviewBundle("task-rebuild", "attempt-1", COMMIT,
                new IndependentReviewBundle.ConsolidatedPatch("patch-1", "evidence://task-rebuild/patch",
                        "d".repeat(64), List.of("src/App.java")),
                new IndependentReviewBundle.FinalManifest(MANIFEST_ID, MANIFEST_URI, MANIFEST_DIGEST),
                List.of(new IndependentReviewBundle.ResultReference("security-1", "security-agent",
                        "evidence://task-rebuild/security", "e".repeat(64))), List.of());
        return new IndependentReviewWorkflow.Request("task-rebuild", "attempt-1", "final-review", COMMIT,
                bundle, null);
    }

    private static void awaitStatus(SoftwareFactoryWorkflow workflow, String expected) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        do {
            if (expected.equals(workflow.status())) return;
            Thread.onSpinWait();
        } while (System.nanoTime() < deadline);
        assertThat(workflow.status()).isEqualTo(expected);
    }

    private static final class RecordingProjectionStore implements UiProjectionStore {
        private UiProjectionSnapshot snapshot;
        private int replacements;

        @Override public void replaceAtomically(UiProjectionSnapshot snapshot) {
            this.snapshot = snapshot;
            replacements++;
        }

        void clear() {
            snapshot = null;
        }
    }
}
