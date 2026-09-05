package com.example.aifactory.service;

import com.example.aifactory.model.TaskState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/** Keeps the HTTP parent observation attached to work that outlives the admission request. */
@Component
public final class AsyncTaskTracer {
    private final ObservationRegistry registry;

    public AsyncTaskTracer(ObservationRegistry registry) {
        this.registry = registry;
    }

    public static AsyncTaskTracer noop() {
        return new AsyncTaskTracer(ObservationRegistry.NOOP);
    }

    public void submit(ExecutorService executor, TaskState task, String operation, Runnable work) {
        if (executor == null || task == null || operation == null || operation.isBlank() || work == null) {
            throw new IllegalArgumentException("Async task span context is incomplete");
        }
        Observation observation = Observation.createNotStarted("ai.factory.task", registry)
                .contextualName(operation)
                .lowCardinalityKeyValue("ai.operation", operation)
                .highCardinalityKeyValue("ai.task.id", task.id)
                .start();
        try {
            executor.submit(() -> {
                try (Observation.Scope ignored = observation.openScope()) {
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
}
