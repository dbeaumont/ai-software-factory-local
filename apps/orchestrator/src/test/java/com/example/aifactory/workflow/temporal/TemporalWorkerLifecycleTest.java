package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.TemporalProperties;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalWorkerLifecycleTest {
    @Test
    void startsAfterRegistrationAndDrainsBeforeForcedShutdown() {
        WorkerFactory factory = mock(WorkerFactory.class);
        Map<String, String> queues = queues();
        when(factory.newWorker(any(), any())).thenReturn(mock(Worker.class));
        TemporalWorkerRegistry registry = new TemporalWorkerRegistry(
                factory, queues, "ai-factory-orchestrator", "0.1.0");
        TemporalWorkerLifecycle lifecycle = new TemporalWorkerLifecycle(factory, registry, properties(queues));
        when(factory.isStarted()).thenReturn(true);
        when(factory.isShutdown()).thenReturn(false);

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        when(factory.isTerminated()).thenReturn(false);
        AtomicBoolean callback = new AtomicBoolean();
        lifecycle.stop(() -> callback.set(true));

        var order = inOrder(factory);
        order.verify(factory).start();
        order.verify(factory).shutdown();
        order.verify(factory).awaitTermination(30_000, TimeUnit.MILLISECONDS);
        order.verify(factory).shutdownNow();
        assertThat(callback).isTrue();
    }

    @Test
    void doesNotStartTwice() {
        WorkerFactory factory = mock(WorkerFactory.class);
        Map<String, String> queues = queues();
        when(factory.newWorker(any(), any())).thenReturn(mock(Worker.class));
        TemporalWorkerLifecycle lifecycle = new TemporalWorkerLifecycle(factory,
                new TemporalWorkerRegistry(factory, queues, "deployment", "build"), properties(queues));

        lifecycle.start();
        lifecycle.start();

        verify(factory).start();
    }

    private static TemporalProperties properties(Map<String, String> queues) {
        return new TemporalProperties("temporal:7233", "ai-factory-local", Duration.ofDays(7),
                "ai-factory-orchestrator", "0.1.0", queues, TemporalProperties.Capacity.defaults(),
                new TemporalProperties.Security(false, "", "", "", ""));
    }

    private static Map<String, String> queues() {
        Map<String, String> queues = new LinkedHashMap<>();
        for (String kind : java.util.List.of("workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm")) {
            queues.put(kind, "ai-factory-" + kind);
        }
        return queues;
    }
}
