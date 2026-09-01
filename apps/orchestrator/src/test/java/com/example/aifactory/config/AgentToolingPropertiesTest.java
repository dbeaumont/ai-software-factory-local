package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolingPropertiesTest {
    @Test
    void keepsAllRolesDisabledBeforeQualification() {
        AgentToolingProperties properties = new AgentToolingProperties(Set.of(), "INCOMPLETE", 0, false);

        assertFalse(properties.enabledFor("planner"));
        assertFalse(properties.enabledFor("reviewer"));
    }

    @Test
    void refusesActivationWithoutEveryThreshold() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("planner"), "INCOMPLETE", 20, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("planner"), "QUALIFIED", 19, true));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("planner"), "QUALIFIED", 20, false));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentToolingProperties(Set.of("developer"), "QUALIFIED", 20, true));
    }

    @Test
    void permitsOnlyTheInitialRolesAfterQualification() {
        AgentToolingProperties properties =
                new AgentToolingProperties(Set.of("planner", "reviewer"), "QUALIFIED", 20, true);

        assertTrue(properties.enabledFor("planner"));
        assertTrue(properties.enabledFor("reviewer"));
        assertFalse(properties.enabledFor("developer"));
    }
}
