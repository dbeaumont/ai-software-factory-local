package com.example.aifactory.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowOperationalMetricsTest {
    @Test
    void exposesRateableCountersForEveryReliabilityEvent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        WorkflowOperationalMetrics metrics = new WorkflowOperationalMetrics(registry);

        metrics.retry("mcp");
        metrics.repair();
        metrics.replan();
        metrics.contradictions(2);
        metrics.escalation();

        assertCount(registry, "retry", "mcp", 1);
        assertCount(registry, "repair", "patch", 1);
        assertCount(registry, "replan", "supervisor", 1);
        assertCount(registry, "contradiction", "consolidation", 2);
        assertCount(registry, "escalation", "human", 1);
    }

    private static void assertCount(SimpleMeterRegistry registry, String event, String source, double count) {
        assertThat(registry.get("ai_workflow_events").tag("event", event).tag("source", source)
                .counter().count()).isEqualTo(count);
    }
}
