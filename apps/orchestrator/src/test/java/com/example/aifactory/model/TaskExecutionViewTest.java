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

    @Test
    void exposesDelegationHierarchyDependenciesStatusAndStopReason() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));
        state.recordDelegation("architecture-1", "supervisor", "architecture-agent", java.util.List.of(),
                "SUCCESS", "SUCCESS_CRITERIA_MET");
        state.recordDelegation("code-1", "supervisor", "code-agent", java.util.List.of("architecture-1"),
                "RUNNING", null);

        assertThat(state.view().delegations()).containsExactly(
                new TaskView.DelegationView("architecture-1", "supervisor", "architecture-agent",
                        java.util.List.of(), "SUCCESS", "SUCCESS_CRITERIA_MET"),
                new TaskView.DelegationView("code-1", "supervisor", "code-agent",
                        java.util.List.of("architecture-1"), "RUNNING", null));

        state.recordDelegation("code-1", "supervisor", "code-agent", java.util.List.of("architecture-1"),
                "FAILED", "BUDGET_EXHAUSTED");
        assertThat(state.view().delegations()).filteredOn(value -> value.delegationId().equals("code-1"))
                .singleElement().extracting(TaskView.DelegationView::stopReason)
                .isEqualTo("BUDGET_EXHAUSTED");
    }

    @Test
    void exposesOnlyArtifactMetadataAndRedactsUnauthorizedUris() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));
        state.recordArtifact("artifact-public", "TEST_RESULT", "COMPLETE", "INTERNAL",
                "evidence://task-1/tests", "a".repeat(64), 512, true);
        state.recordArtifact("artifact-secret", "SECURITY_SCAN", "COMPLETE", "RESTRICTED",
                "evidence://task-1/security", "b".repeat(64), 1_024, false);

        assertThat(state.view().artifacts()).containsExactly(
                new TaskView.ArtifactView("artifact-public", "TEST_RESULT", "COMPLETE", "INTERNAL",
                        "evidence://task-1/tests", "a".repeat(64), 512),
                new TaskView.ArtifactView("artifact-secret", "SECURITY_SCAN", "COMPLETE", "RESTRICTED",
                        null, "b".repeat(64), 1_024));
    }

    @Test
    void exposesContradictionsArbitrationsAndRequiredHumanActions() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));
        state.recordContradiction("contradiction-1", "API compatibility", "FACT", "HIGH", "ESCALATED");
        state.recordDecision("arbitration-1", "contradiction-1", "human-decision", "OPTION_B", "architect");
        state.recordHumanAction("decision-1", "contradiction-1", "ARCHITECTURE", "Choose contract",
                "d".repeat(64), "PENDING");

        TaskView view = state.view();
        assertThat(view.contradictions()).containsExactly(new TaskView.ContradictionView(
                "contradiction-1", "API compatibility", "FACT", "HIGH", "ESCALATED"));
        assertThat(view.decisions()).containsExactly(new TaskView.DecisionView(
                "arbitration-1", "contradiction-1", "human-decision", "OPTION_B", "architect"));
        assertThat(view.humanActions()).containsExactly(new TaskView.HumanActionView(
                "decision-1", "contradiction-1", "ARCHITECTURE", "Choose contract", "d".repeat(64), "PENDING"));
    }

    @Test
    void bindsHumanResponsesToThePendingObjectAndAuthorizedDomain() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));
        state.recordHumanAction("decision-1", "contradiction-1", "SECURITY", "Accept risk?",
                "d".repeat(64), "PENDING");

        state.answerHumanAction("decision-1", "ACCEPT_RISK", "d".repeat(64), "security-owner", "SECURITY");

        assertThat(state.view().humanActions().getFirst().status()).isEqualTo("ANSWERED");
        assertThat(state.view().decisions()).containsExactly(new TaskView.DecisionView(
                "human-decision-1", "contradiction-1", "human-decision", "ACCEPT_RISK", "security-owner"));
        assertThatThrownBy(() -> state.answerHumanAction("decision-1", "REJECT", "d".repeat(64),
                "security-owner", "SECURITY")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancellationIsTerminalAndCancelsPendingHumanActions() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));
        state.recordHumanAction("decision-1", "contradiction-1", "PRODUCT", "Choose behavior",
                "d".repeat(64), "PENDING");

        state.cancel("No longer required", "product-owner");

        assertThat(state.status).isEqualTo(TaskStatus.CANCELLED);
        assertThat(state.view().humanActions().getFirst().status()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> state.transition(TaskStatus.PLANNING, "late transition"))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        state.fail(new IllegalStateException("late failure"));
        assertThat(state.status).isEqualTo(TaskStatus.CANCELLED);
    }
}
