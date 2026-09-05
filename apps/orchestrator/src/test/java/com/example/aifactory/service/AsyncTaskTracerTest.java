package com.example.aifactory.service;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationView;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncTaskTracerTest {
    @Test
    void preservesTheAdmissionParentAcrossTheExecutorBoundary() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        AtomicReference<ObservationView> parentSeenByTask = new AtomicReference<>();
        AtomicReference<ObservationView> parentSeenByChild = new AtomicReference<>();
        AtomicReference<ObservationView> currentInWorker = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                if ("ai.factory.task".equals(context.getName())) {
                    parentSeenByTask.set(context.getParentObservation());
                } else if ("ai.factory.llm".equals(context.getName())) {
                    parentSeenByChild.set(context.getParentObservation());
                }
            }
        });
        AsyncTaskTracer tracer = new AsyncTaskTracer(registry);
        TaskState task = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.invalid/repository.git", "main", "requirement", null));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Observation http = Observation.start("http.server.requests", registry);

        try (Observation.Scope ignored = http.openScope()) {
            tracer.submit(executor, task, "execute", () -> {
                currentInWorker.set(registry.getCurrentObservation());
                new ExecutionTracer(registry).trace(ExecutionTracer.SpanKind.LLM,
                        ExecutionIdentity.deterministic("task-1", "run-1", "node-1", "agent-1"),
                        "planner", () -> {});
                completed.countDown();
            });
        } finally {
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
            http.stop();
            executor.shutdownNow();
        }

        assertThat(parentSeenByTask.get()).isSameAs(http);
        assertThat(currentInWorker.get()).isNotNull();
        assertThat(currentInWorker.get().getContextView().getName()).isEqualTo("ai.factory.task");
        assertThat(parentSeenByChild.get()).isSameAs(currentInWorker.get());
    }
}
