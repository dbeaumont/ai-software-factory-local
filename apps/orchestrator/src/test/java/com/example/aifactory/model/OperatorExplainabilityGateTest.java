package com.example.aifactory.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorExplainabilityGateTest {
    @Test
    void exposesEnoughLinkedFactsToExplainACompleteHierarchicalTrajectory() {
        TaskState state = new TaskState("task-10", "AF-0010",
                new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD));
        state.bindExecution("HIERARCHICAL_ACTIVE", "run-10", "dag-v4", 80_000, 50_000_000, 60);
        state.recordAgentUsage(7, 7_500, 125_000);
        state.recordDelegation("supervisor", null, "supervisor", List.of(), "SUCCESS", "CONSOLIDATED",
                8_000, 2, 1_500, 25_000, List.of("evidence.create_manifest"));
        state.recordDelegation("architecture", "supervisor", "architecture-agent", List.of(), "SUCCESS",
                "SUCCESS_CRITERIA_MET", 5_000, 1, 1_000, 15_000, List.of("context.search_code"));
        state.recordDelegation("code", "supervisor", "code-agent", List.of("architecture"), "SUCCESS",
                "SUCCESS_CRITERIA_MET", 12_000, 2, 2_500, 40_000, List.of("sandbox.apply_patch"));
        state.recordDelegationCodeImpact("code", List.of("src/main/**"), List.of("src/main/App.java"), List.of());
        state.recordDelegation("tests", "supervisor", "test-agent", List.of("code"), "SUCCESS",
                "GATE_PASSED", 6_000, 1, 900, 12_000, List.of("sandbox.run_tests"));
        state.recordDelegation("security", "supervisor", "security-agent", List.of("code"), "SUCCESS",
                "GATE_PASSED", 7_000, 1, 900, 18_000, List.of("sandbox.run_security"));
        state.recordDelegation("review", "supervisor", "independent-reviewer", List.of("tests", "security"),
                "SUCCESS", "REVIEW_ACCEPTED", 4_000, 1, 700, 15_000, List.of("evidence.read"));
        state.recordArtifact("manifest", "FINAL_MANIFEST", "COMPLETE", "INTERNAL",
                "evidence://task-10/manifest", "a".repeat(64), 2_048, true);
        state.recordContradiction("contract", "Choose compatibility strategy", "SCOPE", "MEDIUM", "RESOLVED");
        state.recordHumanAction("contract-choice", "contract", "ARCHITECTURE", "Keep API compatibility?",
                "b".repeat(64), "PENDING", List.of(
                        new TaskView.DecisionOptionView("KEEP", "Keep API", "Adds an adapter", true),
                        new TaskView.DecisionOptionView("BREAK", "Break API", "Requires migration", false)));
        state.answerHumanAction("contract-choice", "KEEP", "b".repeat(64), "architect", "ARCHITECTURE");
        state.pendingEffect = new PendingEffect("scm.create_draft_pull_request", Map.of("branch", "main"),
                "Create a draft pull request", "ALLOW", true);
        state.bindApprovalManifest("c".repeat(64), "evidence://task-10/final-manifest", "d".repeat(64));

        TaskView view = state.view();
        assertThat(view.executionMode()).isEqualTo("HIERARCHICAL_ACTIVE");
        assertThat(view.workflowRunId()).isEqualTo("run-10");
        assertThat(view.delegations()).extracting(TaskView.DelegationView::role).contains(
                "supervisor", "architecture-agent", "code-agent", "test-agent", "security-agent",
                "independent-reviewer");
        assertThat(view.delegations()).allSatisfy(delegation -> {
            assertThat(delegation.durationMillis()).isPositive();
            assertThat(delegation.tokens()).isPositive();
            assertThat(delegation.toolsUsed()).isNotEmpty();
        });
        assertThat(view.artifacts()).singleElement().satisfies(artifact ->
                assertThat(artifact.digest()).isEqualTo("a".repeat(64)));
        assertThat(view.contradictions()).singleElement().satisfies(contradiction ->
                assertThat(contradiction.status()).isEqualTo("RESOLVED"));
        assertThat(view.decisions()).singleElement().satisfies(decision ->
                assertThat(decision.decision()).isEqualTo("KEEP"));
        assertThat(view.humanActions()).singleElement().satisfies(action -> {
            assertThat(action.status()).isEqualTo("ANSWERED");
            assertThat(action.alternatives()).hasSize(2);
        });
        assertThat(view.pendingEffect().manifestId()).isEqualTo("c".repeat(64));
        assertThat(view.pendingEffect().manifestDigest()).isEqualTo("d".repeat(64));
    }
}
