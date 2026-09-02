package com.example.aifactory.service;

import com.example.aifactory.config.AgentToolingProperties;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentActivationGuardTest {
    @Test
    void refusesActiveAndCanaryRolesWithoutQualifiedProductionActivation() {
        AgentActivationGuard guard = new AgentActivationGuard(new AgentToolingProperties(
                Set.of(), "INCOMPLETE", 0, false, true, Set.of("security-agent")));

        assertThatNoException().isThrownBy(() -> guard.requireAllowed(
                "security-agent", "HIERARCHICAL_SHADOW"));
        assertThatThrownBy(() -> guard.requireAllowed("security-agent", "HIERARCHICAL_CANARY"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("until qualification");
        assertThatThrownBy(() -> guard.requireAllowed("security-agent", "HIERARCHICAL_ACTIVE"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("until qualification");
    }

    @Test
    void permitsOnlyExplicitlyEnabledRoleAfterQualification() {
        AgentActivationGuard guard = new AgentActivationGuard(new AgentToolingProperties(
                Set.of("developer"), "QUALIFIED", 36, true, false, Set.of()));

        assertThatNoException().isThrownBy(() -> guard.requireAllowed(
                "developer", "HIERARCHICAL_CANARY"));
        assertThatThrownBy(() -> guard.requireAllowed("security-agent", "HIERARCHICAL_CANARY"))
                .isInstanceOf(SecurityException.class);
    }
}
