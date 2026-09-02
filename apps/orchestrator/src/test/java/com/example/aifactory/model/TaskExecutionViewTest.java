package com.example.aifactory.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskExecutionViewTest {
    @Test
    void exposesHierarchicalModeWorkflowDagVersionAndGlobalBudget() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));
        state.bindExecution("HIERARCHICAL_ACTIVE", "run-123", "dag-v4", 80_000, 80_000_000, 60);
        state.recordAgentUsage(3, 1_200, 45_000);

        TaskView view = state.view();

        assertThat(view.executionMode()).isEqualTo("HIERARCHICAL_ACTIVE");
        assertThat(view.workflowRunId()).isEqualTo("run-123");
        assertThat(view.dagVersion()).isEqualTo("dag-v4");
        assertThat(view.globalBudget()).isEqualTo(
                new TaskView.GlobalBudget(80_000L, 80_000_000L, 60, 1_200, 45_000, 3));
    }

    @Test
    void rejectsIncompleteExecutionMetadata() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));

        assertThatThrownBy(() -> state.bindExecution("HIERARCHICAL_ACTIVE", "", "dag-v4",
                80_000, 80_000_000, 60)).isInstanceOf(IllegalArgumentException.class);
    }
}
