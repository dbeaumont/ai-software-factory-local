package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SupervisorConsolidationGuardTest {
    private final SupervisorConsolidationGuard guard = new SupervisorConsolidationGuard();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allowsConsolidationOnlyWhenEveryDeterministicGatePassed() {
        assertThatCode(() -> guard.enforce(decision("CONSOLIDATE"), gates(null)))
                .doesNotThrowAnyException();
        for (SupervisorConsolidationGuard.Gate failed : SupervisorConsolidationGuard.Gate.values()) {
            assertThatThrownBy(() -> guard.enforce(decision("CONSOLIDATE"), gates(failed)))
                    .isInstanceOf(SecurityException.class).hasMessageContaining(failed.name());
        }
    }

    @Test
    void permitsStoppingReplanningOrEscalatingAfterFailure() {
        for (String action : List.of("STOP", "REPLAN", "REQUEST_MORE_EVIDENCE", "ESCALATE_TO_HUMAN")) {
            assertThatCode(() -> guard.enforce(decision(action),
                    gates(SupervisorConsolidationGuard.Gate.SECURITY))).doesNotThrowAnyException();
        }
    }

    @Test
    void failsClosedWhenAnyRequiredGateOrEvidenceBindingIsMissing() {
        assertThatThrownBy(() -> guard.enforce(decision("CONSOLIDATE"), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("All deterministic");
        List<SupervisorConsolidationGuard.GateResult> partial = new ArrayList<>(gates(null).results());
        partial.removeLast();
        assertThatThrownBy(() -> guard.enforce(decision("CONSOLIDATE"),
                new SupervisorConsolidationGuard.GateBundle(partial)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("All deterministic");
    }

    private tools.jackson.databind.JsonNode decision(String action) {
        return mapper.createObjectNode().put("action", action);
    }

    private static SupervisorConsolidationGuard.GateBundle gates(SupervisorConsolidationGuard.Gate failed) {
        List<SupervisorConsolidationGuard.GateResult> results = new ArrayList<>();
        int digest = 10;
        for (SupervisorConsolidationGuard.Gate gate : SupervisorConsolidationGuard.Gate.values()) {
            results.add(new SupervisorConsolidationGuard.GateResult(gate,
                    gate == failed ? SupervisorConsolidationGuard.Status.FAILED
                            : SupervisorConsolidationGuard.Status.PASSED,
                    Integer.toHexString(digest++).repeat(64)));
        }
        return new SupervisorConsolidationGuard.GateBundle(results);
    }
}
