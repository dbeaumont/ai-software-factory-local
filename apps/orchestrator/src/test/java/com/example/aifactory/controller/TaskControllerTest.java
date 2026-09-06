package com.example.aifactory.controller;

import com.example.aifactory.a2a.A2aTaskAssociationStore;
import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.service.TaskService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

class TaskControllerTest {
    @Test
    void exposesOnlyProjectedEvidenceMetadataByTaskAndArtifact() {
        TaskService service = mock(TaskService.class);
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.recordArtifact("artifact-1", "tests", "COMPLETE", "INTERNAL",
                "evidence://task-1/pipeline-1/tests/" + "a".repeat(64), "a".repeat(64), 42, true);
        when(service.get("task-1")).thenReturn(state.view());
        TaskController controller = new TaskController(service, mock(A2aTaskAssociationStore.class));

        TaskView.ArtifactView result = controller.evidenceSummary("task-1", "artifact-1");

        assertThat(result.uri()).startsWith("evidence://task-1/");
        assertThat(result.digest()).isEqualTo("a".repeat(64));
        assertThatThrownBy(() -> controller.evidenceSummary("task-1", "artifact-unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void navigatesFromTaskAndTemporalExecutionThroughA2aToEvidence() {
        TaskService service = mock(TaskService.class);
        A2aTaskAssociationStore associations = mock(A2aTaskAssociationStore.class);
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.bindExecution("HIERARCHICAL_ACTIVE", "run-1", "dag-v1", 10_000, 100_000, 20);
        state.recordDelegation("delegation-1", "workflow", "developer", java.util.List.of(),
                "COMPLETED", null);
        state.recordArtifact("artifact-1", "patch", "COMPLETE", "INTERNAL",
                "evidence://task-1/attempt-1/patch/" + "a".repeat(64), "a".repeat(64), 42, true);
        when(service.get("task-1")).thenReturn(state.view());
        when(associations.findByDelegation("delegation-1")).thenReturn(Optional.of(
                new A2aTaskAssociationStore.Association("delegation-1", "task-1", "pipeline-1",
                        "ai-factory-task/task-1", "run-1", "b".repeat(40), "message-1", "developer",
                        "c".repeat(64), "a2a-task-1", "a2a-context-1")));

        TaskController.A2aExecutionTraceView trace = new TaskController(service, associations)
                .a2aExecutionTrace("task-1", "delegation-1");

        assertThat(trace.workflowId()).isEqualTo("ai-factory-task/task-1");
        assertThat(trace.workflowRunId()).isEqualTo("run-1");
        assertThat(trace.a2aTaskId()).isEqualTo("a2a-task-1");
        assertThat(trace.a2aContextId()).isEqualTo("a2a-context-1");
        assertThat(trace.evidence()).singleElement()
                .extracting(TaskView.ArtifactView::uri)
                .isEqualTo("evidence://task-1/attempt-1/patch/" + "a".repeat(64));
    }
}
