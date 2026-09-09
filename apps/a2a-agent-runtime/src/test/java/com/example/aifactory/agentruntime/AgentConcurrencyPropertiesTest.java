package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentConcurrencyPropertiesTest {
    @Test
    void rejectsAWorkflowDeadlockTimeoutBelowOneSecond() {
        assertThatThrownBy(() -> properties(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAWorkflowDeadlockTimeoutAboveThirtySeconds() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AgentConcurrencyProperties properties(Duration deadlockTimeout) {
        return new AgentConcurrencyProperties(1, 1, 2, 2, 1, 2, deadlockTimeout,
                Duration.ofSeconds(5));
    }
}
