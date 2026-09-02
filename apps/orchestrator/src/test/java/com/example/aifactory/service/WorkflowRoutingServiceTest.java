package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRoutingServiceTest {
    private final InMemoryRoutingDecisionJournal journal = new InMemoryRoutingDecisionJournal();
    private final WorkflowRoutingService routing = new WorkflowRoutingService(
            new ShortCodePathPlanner(), new HierarchicalPathPlanner(), journal);

    @Test
    void recordsNormalizedFactsMatchedRuleReasonsAndSelectedAgents() {
        RoutingDecision shortDecision = routing.decide(input(
                "short-task", "HIERARCHICAL_ACTIVE", "R1", 1, 1, 2, 1, Set.of(), false));
        RoutingDecision hierarchicalDecision = routing.decide(input(
                "hier-task", "HIERARCHICAL_ACTIVE", "R2", 2, 2, 6, 2,
                Set.of("public-contract"), false));

        assertThat(shortDecision.matchedRule()).isEqualTo("short-code-path");
        assertThat(shortDecision.reasons()).singleElement().asString().contains("Low-risk scope");
        assertThat(shortDecision.normalizedInputs()).containsEntry("modules", "1")
                .containsEntry("impacts", "");
        assertThat(hierarchicalDecision.selectedPath()).isEqualTo("HIERARCHICAL_PATH");
        assertThat(hierarchicalDecision.agents()).containsExactly(
                "supervisor", "architecture-agent", "code-agent", "test-agent", "security-agent",
                "independent-reviewer");
        assertThat(journal.findByTask("short-task")).containsExactly(shortDecision);
        assertThat(journal.countBySelectedPath()).containsEntry("SHORT_CODE_PATH", 1L)
                .containsEntry("HIERARCHICAL_PATH", 1L);
    }

    @Test
    void recordsConservativeFallbackReasonsAndKeepsShadowBaselineAuthoritative() {
        RoutingDecision incomplete = routing.decide(new WorkflowRoutingService.Input(
                "missing-task", "a".repeat(40), "HIERARCHICAL_ACTIVE", "QUALIFIED", "repo", "R1",
                1, 1, 1, 1, Set.of(), false, true, true, false, false, true));
        RoutingDecision shadow = routing.decide(input(
                "shadow-task", "HIERARCHICAL_SHADOW", "R1", 1, 1, 1, 1, Set.of(), false));

        assertThat(incomplete.selectedPath()).isEqualTo("HUMAN_TRIAGE");
        assertThat(incomplete.matchedRule()).isEqualTo("human-triage");
        assertThat(shadow.selectedPath()).isEqualTo("PIPELINE_BASELINE");
        assertThat(shadow.effectiveMode()).isEqualTo("PIPELINE");
        assertThat(shadow.matchedRule()).isEqualTo("shadow-authority");
    }

    @Test
    void producesAnIdempotentDecisionIdForTheSameNormalizedFacts() {
        WorkflowRoutingService.Input input = input(
                "task-1", "HIERARCHICAL_ACTIVE", "R1", 1, 1, 2, 1, Set.of(), false);

        RoutingDecision first = routing.decide(input);
        RoutingDecision replay = routing.decide(input);

        assertThat(replay).isEqualTo(first);
        assertThat(journal.findByTask("task-1")).containsExactly(first);
    }

    private static WorkflowRoutingService.Input input(String taskId, String mode, String risk,
                                                      int modules, int domains, int files, int independentScopes,
                                                      Set<String> impacts, boolean materialDecisionOpen) {
        return new WorkflowRoutingService.Input(taskId, "a".repeat(40), mode, "QUALIFIED", "sample-repo",
                risk, modules, domains, files, independentScopes, impacts, materialDecisionOpen,
                true, true, true, false, true);
    }
}
