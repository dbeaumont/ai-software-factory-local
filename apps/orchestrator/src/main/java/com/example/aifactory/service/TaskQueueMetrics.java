package com.example.aifactory.service;

import com.example.aifactory.config.TaskQueueObservabilityProperties;
import com.example.aifactory.config.TemporalProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Measures Temporal task queue schedule-to-start latency and bounded worker saturation by perimeter. */
@Component
public final class TaskQueueMetrics {
    private final MeterRegistry registry;
    private final Map<String, String> perimeterByQueue;
    private final Map<String, AtomicInteger> activeByPerimeter = new LinkedHashMap<>();
    private final int workerCapacity;

    @Autowired
    public TaskQueueMetrics(MeterRegistry registry, TemporalProperties temporal,
                            TaskQueueObservabilityProperties properties) {
        this(registry, temporal.taskQueues(), properties.workerCapacity());
    }

    TaskQueueMetrics(MeterRegistry registry, Map<String, String> queues, int workerCapacity) {
        this.registry = registry;
        this.workerCapacity = workerCapacity;
        Map<String, String> reverse = new LinkedHashMap<>();
        queues.forEach((perimeter, queue) -> {
            reverse.put(queue, perimeter);
            AtomicInteger active = new AtomicInteger();
            activeByPerimeter.put(perimeter, active);
            Gauge.builder("ai_task_queue_active", active, AtomicInteger::get)
                    .tag("perimeter", perimeter).register(registry);
            Gauge.builder("ai_task_queue_saturation_ratio", active,
                            value -> Math.min(1.0, value.doubleValue() / workerCapacity))
                    .tag("perimeter", perimeter).register(registry);
        });
        this.perimeterByQueue = Map.copyOf(reverse);
    }

    public static TaskQueueMetrics noop() {
        return new TaskQueueMetrics(new SimpleMeterRegistry(), Map.of("unknown", "unknown"), 1);
    }

    public Lease start(String taskQueue, long scheduledTimestampMillis, long startedTimestampMillis) {
        String perimeter = perimeterByQueue.getOrDefault(taskQueue, "unknown");
        long waitMillis = Math.max(0, startedTimestampMillis - scheduledTimestampMillis);
        Timer.builder("ai_task_queue_wait").tag("perimeter", perimeter)
                .register(registry).record(waitMillis, TimeUnit.MILLISECONDS);
        AtomicInteger active = activeByPerimeter.get(perimeter);
        if (active == null) return () -> { };
        active.incrementAndGet();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) active.decrementAndGet();
        };
    }

    @FunctionalInterface
    public interface Lease extends AutoCloseable {
        @Override void close();
    }
}
