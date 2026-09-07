package com.example.aifactory.a2a;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.temporal.TemporalCommandConflictException;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aRetryReconcilerTest {
    private static final String DIGEST = "d".repeat(64);
    private final A2aTaskAssociationStore associations = mock(A2aTaskAssociationStore.class);
    private final A2aClient client = mock(A2aClient.class);
    private final A2aAgentWorkflowProbe workflows = mock(A2aAgentWorkflowProbe.class);

    @Test
    void failsClosedWhenTheAssociationIsMissing() {
        when(associations.findByDelegation("code-1")).thenReturn(Optional.empty());

        assertReconciliationRequired(() -> reconciler().requireSafeRetry(task(), "code-1"),
                "persisted task association");
    }

    @Test
    void refusesToRetryAnActiveA2aTaskEvenWhenItsWorkflowLooksClosed() {
        arrange(A2aContracts.TaskState.WORKING, List.of());
        when(workflows.status("developer", "a2a-task-1", Duration.ofSeconds(2)))
                .thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);

        assertReconciliationRequired(() -> reconciler().requireSafeRetry(task(), "code-1"),
                "still active");
    }

    @Test
    void refusesToRetryACompletedTaskAndValidatesItsEvidence() {
        arrange(A2aContracts.TaskState.COMPLETED, List.of(evidenceArtifact()));
        when(workflows.status("developer", "a2a-task-1", Duration.ofSeconds(2)))
                .thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);

        assertReconciliationRequired(() -> reconciler().requireSafeRetry(task(), "code-1"),
                "already completed");
    }

    @Test
    void rejectsAnUnboundEvidenceReferenceBeforeConsideringARetry() {
        A2aContracts.Part unbound = new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                Map.of("schema_version", "1", "uri", "evidence://other/pipeline-1/output/" + DIGEST,
                        "digest", DIGEST, "contract", "code-patch-v1"),
                URI.create("evidence://other/pipeline-1/output/" + DIGEST));
        arrange(A2aContracts.TaskState.FAILED,
                List.of(new A2aContracts.Artifact("artifact-1", "output", List.of(unbound), Map.of())));

        assertReconciliationRequired(() -> reconciler().requireSafeRetry(task(), "code-1"),
                "artifacts could not be reconciled");
        verify(workflows, never()).status("developer", "a2a-task-1", Duration.ofSeconds(2));
    }

    @Test
    void refusesToRetryUntilTheAgentWorkflowIsClosed() {
        arrange(A2aContracts.TaskState.FAILED, List.of());
        when(workflows.status("developer", "a2a-task-1", Duration.ofSeconds(2)))
                .thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);

        assertReconciliationRequired(() -> reconciler().requireSafeRetry(task(), "code-1"),
                "workflow is not closed");
    }

    @Test
    void permitsRetryOnlyAfterFailedTaskAndClosedWorkflowAreReconciled() {
        arrange(A2aContracts.TaskState.FAILED, List.of(evidenceArtifact()));
        when(workflows.status("developer", "a2a-task-1", Duration.ofSeconds(2)))
                .thenReturn(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED);

        assertThatCode(() -> reconciler().requireSafeRetry(task(), "code-1")).doesNotThrowAnyException();
    }

    private void arrange(A2aContracts.TaskState state, List<A2aContracts.Artifact> artifacts) {
        A2aTaskAssociationStore.Association association = association();
        when(associations.findByDelegation("code-1")).thenReturn(Optional.of(association));
        when(client.getTask(new A2aContracts.TaskQuery("developer", "a2a-task-1", 1)))
                .thenReturn(CompletableFuture.completedFuture(new A2aContracts.TaskSnapshot(
                        "a2a-task-1", "context-1", state, Instant.now(), artifacts, Map.of())));
    }

    private static A2aContracts.Artifact evidenceArtifact() {
        String uri = "evidence://task-1/pipeline-1/output/" + DIGEST;
        A2aContracts.Part part = new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                Map.of("schema_version", "1", "uri", uri, "digest", DIGEST,
                        "contract", "code-patch-v1"), URI.create(uri));
        return new A2aContracts.Artifact("artifact-1", "output", List.of(part), Map.of());
    }

    private A2aRetryReconciler reconciler() {
        return new A2aRetryReconciler(associations, client, workflows, Duration.ofSeconds(2));
    }

    private static A2aTaskAssociationStore.Association association() {
        return new A2aTaskAssociationStore.Association("code-1", "task-1", "pipeline-1",
                "ai-factory-task-v1/task-1/pipeline-1", "parent-run", "source-commit", "message-1",
                "developer", "a".repeat(64), "a2a-task-1", "context-1");
    }

    private static TaskState task() {
        TaskState task = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        task.recordDelegation("code-1", "supervisor", "developer", List.of(), "FAILED", "TIMEOUT");
        return task;
    }

    private static void assertReconciliationRequired(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
                                                     String message) {
        assertThatThrownBy(action).isInstanceOf(TemporalCommandConflictException.class)
                .hasMessageContaining(message)
                .extracting(error -> ((TemporalCommandConflictException) error).reason())
                .isEqualTo(TemporalCommandConflictException.Reason.RECONCILIATION_REQUIRED);
    }
}
