package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskQueueObservabilityPropertiesTest {
    @Test
    void rejectsAnInvalidWorkerCapacity() {
        assertThatThrownBy(() -> new TaskQueueObservabilityProperties(0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
        assertThatThrownBy(() -> new TaskQueueObservabilityProperties(1_001))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("capacity");
    }
}
