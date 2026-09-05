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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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

    @Test
    void runsParallelTasksAsOverlappingSiblingsOfTheSameParent() throws Exception {
        ObservationRegistry registry = ObservationRegistry.create();
        List<Observation.Context> startedTasks = new CopyOnWriteArrayList<>();
        List<Observation.Context> stoppedTasks = new CopyOnWriteArrayList<>();
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(2);
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                if ("ai.factory.task".equals(context.getName())) {
                    startedTasks.add(context);
                }
            }

            @Override
            public void onStop(Observation.Context context) {
                if ("ai.factory.task".equals(context.getName())) {
                    stoppedTasks.add(context);
                }
            }
        });
        AsyncTaskTracer tracer = new AsyncTaskTracer(registry);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        Observation http = Observation.start("http.server.requests", registry);
        Runnable overlappingWork = () -> {
            bothStarted.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("parallel test timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                completed.countDown();
            }
        };

        try (Observation.Scope ignored = http.openScope()) {
            tracer.submit(executor, task("task-1"), "execute", overlappingWork);
            tracer.submit(executor, task("task-2"), "execute", overlappingWork);
            assertThat(bothStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(startedTasks).hasSize(2);
            assertThat(stoppedTasks).isEmpty();
            assertThat(startedTasks).allSatisfy(context -> assertThat(context.getParentObservation()).isSameAs(http));
            release.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            http.stop();
            executor.shutdownNow();
        }

        for (int attempt = 0; attempt < 50 && stoppedTasks.size() < 2; attempt++) Thread.sleep(10);
        assertThat(stoppedTasks).containsExactlyInAnyOrderElementsOf(startedTasks);
    }

    private static TaskState task(String id) {
        return new TaskState(id, "AF-0001",
                new TaskRequest("https://example.invalid/repository.git", "main", "requirement", null));
    }
}
