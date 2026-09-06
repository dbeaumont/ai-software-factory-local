package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

class A2aTelemetryCorrelationTest {
    @Test
    void scopesIdentifiersToLogsAndKeepsMetricTagsBounded() {
        try (A2aTelemetryCorrelation ignored = A2aTelemetryCorrelation.open("task-1", "workflow-1", "message-1")) {
            assertThat(MDC.get("task.id")).isEqualTo("task-1");
            assertThat(MDC.get("workflow.id")).isEqualTo("workflow-1");
            assertThat(MDC.get("message.id")).isEqualTo("message-1");
        }
        assertThat(MDC.get("task.id")).isNull();

        A2aMetricDimensions dimensions = new A2aMetricDimensions(
                "developer", "developer.code-task-v1", "execute", "1.0", "working");
        assertThat(dimensions.tags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .noneMatch(key -> key.contains("id"));
    }
}
