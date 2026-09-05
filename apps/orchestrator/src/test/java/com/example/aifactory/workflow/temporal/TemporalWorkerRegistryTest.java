package com.example.aifactory.workflow.temporal;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalWorkerRegistryTest {
    @Test
    void createsEveryRequiredWorkerExactlyOnItsConfiguredQueue() {
        WorkerFactory factory = mock(WorkerFactory.class);
        Map<String, String> queues = queues();
        queues.values().forEach(queue -> when(factory.newWorker(queue)).thenReturn(mock(Worker.class)));

        TemporalWorkerRegistry registry = new TemporalWorkerRegistry(factory, queues);

        assertThat(registry.workers()).containsOnlyKeys(
                "workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm");
        queues.forEach((kind, queue) -> {
            assertThat(registry.worker(kind)).isNotNull();
            verify(factory).newWorker(queue);
        });
    }

    @Test
    void rejectsMissingOrAliasedTaskQueues() {
        Map<String, String> missing = queues();
        missing.remove("scm");
        assertThatThrownBy(() -> new TemporalWorkerRegistry(mock(WorkerFactory.class), missing))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, String> duplicate = queues();
        duplicate.put("scm", duplicate.get("evidence"));
        assertThatThrownBy(() -> new TemporalWorkerRegistry(mock(WorkerFactory.class), duplicate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, String> queues() {
        Map<String, String> queues = new LinkedHashMap<>();
        for (String kind : java.util.List.of("workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm")) {
            queues.put(kind, "ai-factory-" + kind);
        }
        return queues;
    }
}
