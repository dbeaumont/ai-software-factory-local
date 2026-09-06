package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aCatalogCoherenceGateTest {

    @Test
    void acceptsTheCompleteCatalogCardsContractsUrlsAndPermissions() {
        assertThatCode(() -> new A2aCatalogCoherenceGate(new ObjectMapper(), new AgentCatalog()).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void failsClosedOnAnyCatalogSetDivergence() {
        assertThatThrownBy(() -> A2aCatalogCoherenceGate.requireExact(
                "skills", Set.of("developer"), Set.of("patch-repair")))
                .isInstanceOf(A2aCatalogCoherenceGate.CoherenceViolation.class)
                .hasMessageContaining("skills diverge");
    }
}
