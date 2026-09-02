package com.example.aifactory.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TaskQueueMetricsTest {
    @Test
    void recordsWaitAndLiveSaturationByDeclaredPerimeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskQueueMetrics metrics = new TaskQueueMetrics(registry, Map.of(
                "llm", "ai-factory-llm", "sandbox", "ai-factory-sandbox"), 4);

        TaskQueueMetrics.Lease first = metrics.start("ai-factory-llm", 1_000, 1_025);
        TaskQueueMetrics.Lease second = metrics.start("ai-factory-llm", 2_000, 2_040);

        assertThat(registry.get("ai_task_queue_wait").tag("perimeter", "llm").timer()
                .totalTime(TimeUnit.MILLISECONDS)).isEqualTo(65);
        assertThat(registry.get("ai_task_queue_active").tag("perimeter", "llm").gauge().value()).isEqualTo(2);
        assertThat(registry.get("ai_task_queue_saturation_ratio").tag("perimeter", "llm")
                .gauge().value()).isEqualTo(0.5);
        first.close();
        first.close();
        second.close();
        assertThat(registry.get("ai_task_queue_active").tag("perimeter", "llm").gauge().value()).isZero();
    }

    @Test
    void doesNotCreateMetricsForAnUndeclaredQueueName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TaskQueueMetrics metrics = new TaskQueueMetrics(registry, Map.of("llm", "ai-factory-llm"), 4);

        metrics.start("task-controlled-name", 1_000, 1_100).close();

        assertThat(registry.find("ai_task_queue_active").tag("perimeter", "task-controlled-name").gauge())
                .isNull();
    }
}
