package com.example.aifactory.service;

import com.example.aifactory.model.TaskState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/** Keeps the HTTP parent observation attached to work that outlives the admission request. */
@Component
public final class AsyncTaskTracer {
    private final ObservationRegistry registry;
    private final Tracer tracer;
    private final ConcurrentMap<String, TraceContext> taskContexts = new ConcurrentHashMap<>();

    public AsyncTaskTracer(ObservationRegistry registry) {
        this(registry, Tracer.NOOP);
    }

    @Autowired
    public AsyncTaskTracer(ObservationRegistry registry, Tracer tracer) {
        this.registry = registry;
        this.tracer = tracer;
    }

    public static AsyncTaskTracer noop() {
        return new AsyncTaskTracer(ObservationRegistry.NOOP);
    }

    public void submit(ExecutorService executor, TaskState task, String operation, Runnable work) {
        if (executor == null || task == null || operation == null || operation.isBlank() || work == null) {
            throw new IllegalArgumentException("Async task span context is incomplete");
        }
        Observation observation = startObservation(task, operation);
        try {
            executor.submit(() -> {
                try (Observation.Scope ignored = observation.openScope()) {
                    rememberTaskContext(task.id);
                    work.run();
                    observation.lowCardinalityKeyValue("ai.outcome", task.status.name().toLowerCase());
                } catch (RuntimeException exception) {
                    observation.error(exception);
                    observation.lowCardinalityKeyValue("ai.outcome", "failed");
                    throw exception;
                } finally {
                    observation.stop();
                }
            });
        } catch (RuntimeException exception) {
            observation.error(exception);
            observation.lowCardinalityKeyValue("ai.outcome", "rejected");
            observation.stop();
            throw exception;
        }
    }

    private Observation startObservation(TaskState task, String operation) {
        TraceContext previous = "resume-after-approval".equals(operation)
                ? taskContexts.remove(task.id) : taskContexts.get(task.id);
        if ("resume-after-approval".equals(operation) && previous != null) {
            Span.Builder builder = tracer.spanBuilder()
                    .name("ai.factory.task.continuation")
                    .tag("ai.operation", operation)
                    .tag("ai.task.id", task.id)
                    .addLink(new Link(previous, Map.of("ai.link.type", "continuation")));
            Span parent = tracer.currentSpan();
            if (parent != null) builder.setParent(parent.context());
            Span continuation = builder.start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(continuation)) {
                return newObservation(task, operation);
            } finally {
                continuation.end();
            }
        }
        return newObservation(task, operation);
    }

    private Observation newObservation(TaskState task, String operation) {
        return Observation.createNotStarted("ai.factory.task", registry)
                .contextualName(operation)
                .lowCardinalityKeyValue("ai.operation", operation)
                .highCardinalityKeyValue("ai.task.id", task.id)
                .start();
    }

    private void rememberTaskContext(String taskId) {
        Span active = tracer.currentSpan();
        if (active == null) return;
        TraceContext context = active.context();
        if (context != null && context.traceId() != null && context.traceId().matches("[0-9a-f]{32}")) {
            taskContexts.put(taskId, context);
        }
    }
}
