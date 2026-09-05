package com.example.aifactory.controller;

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

class TaskControllerTest {
    @Test
    void exposesOnlyProjectedEvidenceMetadataByTaskAndArtifact() {
        TaskService service = mock(TaskService.class);
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.recordArtifact("artifact-1", "tests", "COMPLETE", "INTERNAL",
                "evidence://task-1/pipeline-1/tests/" + "a".repeat(64), "a".repeat(64), 42, true);
        when(service.get("task-1")).thenReturn(state.view());
        TaskController controller = new TaskController(service);

        TaskView.ArtifactView result = controller.evidenceSummary("task-1", "artifact-1");

        assertThat(result.uri()).startsWith("evidence://task-1/");
        assertThat(result.digest()).isEqualTo("a".repeat(64));
        assertThatThrownBy(() -> controller.evidenceSummary("task-1", "artifact-unknown"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
