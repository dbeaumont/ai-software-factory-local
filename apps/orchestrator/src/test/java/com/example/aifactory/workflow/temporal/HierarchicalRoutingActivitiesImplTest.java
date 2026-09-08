package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.TaskRoutingFacts;
import com.example.aifactory.service.HierarchicalPathPlanner;
import com.example.aifactory.service.InMemoryRoutingDecisionJournal;
import com.example.aifactory.service.ShortCodePathPlanner;
import com.example.aifactory.service.WorkflowRoutingService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalRoutingActivitiesImplTest {
    private final InMemoryRoutingDecisionJournal journal = new InMemoryRoutingDecisionJournal();
    private final HierarchicalRoutingActivitiesImpl activity = new HierarchicalRoutingActivitiesImpl(
            new WorkflowRoutingService(new ShortCodePathPlanner(), new HierarchicalPathPlanner(), journal));

    @Test
    void mapsPersistedAdmissionFactsToTheHostRoutingPolicy() {
        TaskRoutingFacts facts = new TaskRoutingFacts("QUALIFIED", "R2", 2, 2, 8, 2,
                Set.of("public-contract"), false, true, false, true);

        HierarchicalRoutingActivities.Decision decision = activity.decide(
                new HierarchicalRoutingActivities.Request("task-1", "customer-api", "a".repeat(40), facts));

        assertThat(decision.selectedPath()).isEqualTo("HIERARCHICAL_PATH");
        assertThat(decision.humanGate()).isEqualTo("BEFORE_EXTERNAL_EFFECT");
        assertThat(decision.normalizedInputs()).containsEntry("risk", "R2")
                .containsEntry("impacts", "public-contract");
        assertThat(journal.findByTask("task-1")).hasSize(1);
    }

    @Test
    void routesIncompleteFactsToHumanTriageWithoutInventingDefaults() {
        TaskRoutingFacts facts = new TaskRoutingFacts(null, null, 0, 0, 0, 0,
                Set.of(), false, false, false, false);

        HierarchicalRoutingActivities.Decision decision = activity.decide(
                new HierarchicalRoutingActivities.Request("task-2", "customer-api", "b".repeat(40), facts));

        assertThat(decision.selectedPath()).isEqualTo("HUMAN_TRIAGE");
        assertThat(decision.matchedRule()).isEqualTo("human-triage");
    }
}
