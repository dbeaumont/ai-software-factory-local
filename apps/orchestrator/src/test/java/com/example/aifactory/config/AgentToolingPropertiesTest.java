package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolingPropertiesTest {
    @Test
    void keepsAllRolesDisabledBeforeQualification() {
        AgentToolingProperties properties = new AgentToolingProperties(Set.of(), "INCOMPLETE", 0, false, false, Set.of());

        assertFalse(properties.enabledFor("planner"));
        assertFalse(properties.enabledFor("reviewer"));
    }

    @Test
    void refusesActivationWithoutEveryThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("planner"), "INCOMPLETE", 20, true, false, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("planner"), "QUALIFIED", 35, true, false, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("planner"), "QUALIFIED", 36, false, false, Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("invented-role"), "QUALIFIED", 36, true, false, Set.of()));
    }

    @Test
    void permitsOnlyTheInitialRolesAfterQualification() {
        AgentToolingProperties properties =
                new AgentToolingProperties(Set.of("planner", "reviewer"), "QUALIFIED", 36, true, false, Set.of());

        assertTrue(properties.enabledFor("planner"));
        assertTrue(properties.enabledFor("reviewer"));
        assertFalse(properties.enabledFor("developer"));
    }

    @Test
    void allowsExplicitEvaluationRolesWithoutClaimingProductionQualification() {
        AgentToolingProperties properties = new AgentToolingProperties(
                Set.of(), "INCOMPLETE", 0, false, true, Set.of("planner", "reviewer"));
        assertTrue(properties.enabledFor("planner"));
        assertTrue(properties.enabledFor("reviewer"));
    }

    @Test
    void keepsEveryHierarchicalRoleDisabledUntilQualification() {
        Set<String> roles = Set.of("supervisor", "architecture-agent", "impact-analysis",
                "dependencies-contracts", "code-agent", "developer", "patch-repair", "test-agent",
                "test-design", "test-evidence", "security-agent", "threat-model", "security-findings",
                "independent-reviewer");

        for (String role : roles) {
            assertThrows(IllegalArgumentException.class, () -> new AgentToolingProperties(
                    Set.of(role), "INCOMPLETE", 36, true, false, Set.of()));
        }
    }
}
