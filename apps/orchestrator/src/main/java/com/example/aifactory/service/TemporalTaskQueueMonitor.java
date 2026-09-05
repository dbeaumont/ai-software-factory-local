package com.example.aifactory.service;

import com.example.aifactory.config.TemporalProperties;
import io.grpc.Deadline;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.enums.v1.TaskQueueType;
import io.temporal.api.taskqueue.v1.TaskQueue;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueRequest;
import io.temporal.api.workflowservice.v1.DescribeTaskQueueResponse;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** Periodically exports bounded server-side queue depth, pollers and durable human-wait state. */
@Component
public final class TemporalTaskQueueMonitor {
    private static final Logger LOGGER = LoggerFactory.getLogger(TemporalTaskQueueMonitor.class);
    private static final TaskQueueType[] TYPES = {
            TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW, TaskQueueType.TASK_QUEUE_TYPE_ACTIVITY
    };

    private final String namespace;
    private final Map<String, String> queues;
    private final Function<DescribeTaskQueueRequest, DescribeTaskQueueResponse> describe;
    private final LongSupplier waitingHuman;
    private final Map<Key, AtomicLong> backlog = new LinkedHashMap<>();
    private final Map<Key, AtomicLong> pollers = new LinkedHashMap<>();
    private final Map<Key, Counter> failures = new LinkedHashMap<>();
    private final AtomicLong waitingHumanValue = new AtomicLong();
    private final Counter humanQueryFailures;

    @Autowired
    public TemporalTaskQueueMonitor(MeterRegistry registry, TemporalProperties properties,
                                    WorkflowServiceStubs service, JdbcTemplate jdbc) {
        this(registry, properties.namespace(), properties.taskQueues(), request ->
                        service.blockingStub()
                                .withDeadline(Deadline.after(5, TimeUnit.SECONDS))
                                .describeTaskQueue(request),
                () -> {
                    Long count = jdbc.queryForObject("""
                            SELECT count(*) FROM tasks t
                             WHERE t.status = 'WAITING_APPROVAL'
                                OR EXISTS (SELECT 1 FROM human_actions h
                                            WHERE h.task_id = t.task_id AND h.status = 'PENDING')
                            """, Long.class);
                    return count == null ? 0 : count;
                });
    }

    TemporalTaskQueueMonitor(MeterRegistry registry, String namespace, Map<String, String> queues,
                             Function<DescribeTaskQueueRequest, DescribeTaskQueueResponse> describe,
                             LongSupplier waitingHuman) {
        this.namespace = namespace;
        this.queues = Map.copyOf(queues);
        this.describe = describe;
        this.waitingHuman = waitingHuman;
        queues.keySet().stream().sorted().forEach(perimeter -> {
            for (TaskQueueType type : TYPES) {
                Key key = new Key(perimeter, type);
                String typeTag = typeTag(type);
                AtomicLong backlogValue = new AtomicLong();
                AtomicLong pollerValue = new AtomicLong();
                backlog.put(key, backlogValue);
                pollers.put(key, pollerValue);
                Gauge.builder("ai_temporal_task_queue_backlog", backlogValue, AtomicLong::get)
                        .tags("perimeter", perimeter, "task_type", typeTag).register(registry);
                Gauge.builder("ai_temporal_task_queue_pollers", pollerValue, AtomicLong::get)
                        .tags("perimeter", perimeter, "task_type", typeTag).register(registry);
                failures.put(key, Counter.builder("ai_temporal_task_queue_probe_failures")
                        .tags("perimeter", perimeter, "task_type", typeTag).register(registry));
            }
        });
        Gauge.builder("ai_temporal_workflows_waiting_human", waitingHumanValue, AtomicLong::get)
                .register(registry);
        humanQueryFailures = Counter.builder("ai_temporal_human_wait_probe_failures").register(registry);
    }

    @Scheduled(fixedDelayString = "${ai-factory.temporal.queue-metrics-delay:PT15S}")
    public void collect() {
        queues.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            for (TaskQueueType type : TYPES) collect(entry.getKey(), entry.getValue(), type);
        });
        try {
            waitingHumanValue.set(Math.max(0, waitingHuman.getAsLong()));
        } catch (RuntimeException failure) {
            humanQueryFailures.increment();
            LOGGER.warn("Temporal human-wait metric query failed: {}", failure.getClass().getSimpleName());
        }
    }

    private void collect(String perimeter, String queue, TaskQueueType type) {
        Key key = new Key(perimeter, type);
        try {
            DescribeTaskQueueResponse response = describe.apply(DescribeTaskQueueRequest.newBuilder()
                    .setNamespace(namespace)
                    .setTaskQueue(TaskQueue.newBuilder().setName(queue))
                    .setTaskQueueType(type)
                    .setReportStats(true)
                    .setIncludeTaskQueueStatus(true)
                    .setReportPollers(true)
                    .build());
            long count = response.hasStats() ? response.getStats().getApproximateBacklogCount()
                    : response.hasTaskQueueStatus() ? response.getTaskQueueStatus().getBacklogCountHint() : 0;
            backlog.get(key).set(Math.max(0, count));
            pollers.get(key).set(response.getPollersCount());
        } catch (RuntimeException failure) {
            failures.get(key).increment();
            LOGGER.warn("Temporal task queue probe failed for perimeter={} type={}: {}",
                    perimeter, typeTag(type), failure.getClass().getSimpleName());
        }
    }

    private static String typeTag(TaskQueueType type) {
        return type == TaskQueueType.TASK_QUEUE_TYPE_WORKFLOW ? "workflow" : "activity";
    }

    private record Key(String perimeter, TaskQueueType type) {}
}
