package com.example.aifactory.model;

import java.util.Set;

/** User-supplied, auditable facts used by the host-owned workflow routing policy. */
public record TaskRoutingFacts(
        String qualification,
        String risk,
        int modules,
        int domains,
        int estimatedFiles,
        int independentCodeScopes,
        Set<String> impacts,
        boolean materialDecisionOpen,
        boolean inputsComplete,
        boolean contradictory,
        boolean budgetAvailable) {
    public TaskRoutingFacts {
        impacts = impacts == null ? Set.of() : Set.copyOf(impacts);
    }

    /** Compatibility fixture for internal callers; public admissions must still send the facts object. */
    public static TaskRoutingFacts qualifiedLowRiskFixture() {
        return new TaskRoutingFacts("QUALIFIED", "R1", 1, 1, 1, 1, Set.of(),
                false, true, false, true);
    }
}
