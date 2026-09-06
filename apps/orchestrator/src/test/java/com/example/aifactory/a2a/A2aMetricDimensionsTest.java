package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aMetricDimensionsTest {
    @Test
    void exposesOnlyTheFiveBoundedProtocolDimensions() {
        A2aMetricDimensions dimensions = new A2aMetricDimensions(
                "developer", "developer.code-task-v1", "send", "1.0", "submitted");
        assertThat(dimensions.tags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrder("agent.role", "agent.skill", "rpc.operation", "a2a.version", "task.state")
                .noneMatch(key -> key.contains("id"));
        assertThatThrownBy(() -> new A2aMetricDimensions(
                "tenant-controlled", "developer.code-task-v1", "send", "1.0", "submitted"))
                .hasMessageContaining("role");
    }
}
