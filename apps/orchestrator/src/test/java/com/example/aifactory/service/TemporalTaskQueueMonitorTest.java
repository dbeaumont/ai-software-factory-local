package com.example.aifactory.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.PollerInfo;
import io.temporal.api.taskqueue.v1.TaskQueueStats;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalTaskQueueMonitorTest {
    @Test
    void exportsBacklogPollersAndWaitingHumanWithBoundedDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        List<DescribeTaskQueueRequest> requests = new ArrayList<>();
        TemporalTaskQueueMonitor monitor = new TemporalTaskQueueMonitor(registry, "ai-factory-local",
                Map.of("llm", "ai-factory-llm"), request -> {
                    requests.add(request);
                    long backlog = request.getTaskQueueType() == TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW ? 2 : 7;
                    return DescribeTaskQueueResponse.newBuilder()
                            .setStats(TaskQueueStats.newBuilder().setApproximateBacklogCount(backlog))
                            .addPollers(PollerInfo.getDefaultInstance())
                            .build();
                }, () -> 3, () -> 42);

        monitor.collect();

        assertThat(requests).hasSize(2).allSatisfy(request -> {
            assertThat(request.getNamespace()).isEqualTo("ai-factory-local");
            assertThat(request.getTaskQueue().getName()).isEqualTo("ai-factory-llm");
            assertThat(request.getReportStats()).isTrue();
            assertThat(request.getReportPollers()).isTrue();
        });
        assertThat(registry.get("ai_temporal_task_queue_backlog")
                .tags("perimeter", "llm", "task_type", "workflow").gauge().value()).isEqualTo(2);
        assertThat(registry.get("ai_temporal_task_queue_backlog")
                .tags("perimeter", "llm", "task_type", "activity").gauge().value()).isEqualTo(7);
        assertThat(registry.get("ai_temporal_task_queue_pollers")
                .tags("perimeter", "llm", "task_type", "activity").gauge().value()).isEqualTo(1);
        assertThat(registry.get("ai_temporal_workflows_waiting_human").gauge().value()).isEqualTo(3);
        assertThat(registry.get("ai_temporal_projection_lag_seconds").gauge().value()).isEqualTo(42);
    }

    @Test
    void keepsLastValuesAndCountsProbeFailuresWithoutStoppingTheScan() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TemporalTaskQueueMonitor monitor = new TemporalTaskQueueMonitor(registry, "ai-factory-local",
                Map.of("llm", "ai-factory-llm"), request -> {
                    if (request.getTaskQueueType() == TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW) {
                        throw new IllegalStateException("unavailable");
                    }
                    return DescribeTaskQueueResponse.getDefaultInstance();
                }, () -> { throw new IllegalStateException("database unavailable"); },
                () -> { throw new IllegalStateException("database unavailable"); });

        monitor.collect();

        assertThat(registry.get("ai_temporal_task_queue_probe_failures")
                .tags("perimeter", "llm", "task_type", "workflow").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_temporal_human_wait_probe_failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai_temporal_projection_probe_failures").counter().count()).isEqualTo(1);
    }
}
