package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.TemporalProperties;
import io.temporal.api.workflowservice.v1.WorkflowServiceGrpc;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporalEngineHealthIndicatorTest {
    @Test
    void isUpOnlyWhenNamespaceAndAllWorkersAreActive() {
        Fixture fixture = fixture();
        when(fixture.factory.isStarted()).thenReturn(true);
        when(fixture.factory.isShutdown()).thenReturn(false);

        var health = fixture.indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ticketEngine", "ACTIVE")
                .containsEntry("registeredWorkers", 7);
    }

    @Test
    void suspendsReadinessBeforeWorkersStart() {
        Fixture fixture = fixture();
        when(fixture.factory.isStarted()).thenReturn(false);

        var health = fixture.indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("infrastructure", "AVAILABLE")
                .containsEntry("ticketEngine", "ADMISSIONS_SUSPENDED");
    }

    @Test
    void isDownWhenNamespaceCannotBeDescribed() {
        Fixture fixture = fixture();
        when(fixture.stub.describeNamespace(any())).thenThrow(new IllegalStateException("unavailable"));

        var health = fixture.indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("infrastructure", "UNAVAILABLE")
                .containsEntry("ticketEngine", "ADMISSIONS_SUSPENDED");
    }

    private static Fixture fixture() {
        WorkerFactory factory = mock(WorkerFactory.class);
        Map<String, String> queues = queues();
        queues.forEach((kind, queue) -> when(factory.newWorker(any(), any())).thenReturn(mock(Worker.class)));
        TemporalWorkerRegistry registry = new TemporalWorkerRegistry(
                factory, queues, "ai-factory-orchestrator", "0.1.0");
        WorkflowServiceStubs service = mock(WorkflowServiceStubs.class);
        WorkflowServiceGrpc.WorkflowServiceBlockingStub stub =
                mock(WorkflowServiceGrpc.WorkflowServiceBlockingStub.class);
        when(service.blockingStub()).thenReturn(stub);
        when(stub.withDeadlineAfter(2, TimeUnit.SECONDS)).thenReturn(stub);
        TemporalProperties properties = new TemporalProperties("temporal:7233", "ai-factory-local",
                Duration.ofDays(7), "ai-factory-orchestrator", "0.1.0", queues,
                TemporalProperties.Capacity.defaults(), new TemporalProperties.Security(false, "", "", "", ""));
        return new Fixture(factory, stub,
                new TemporalEngineHealthIndicator(service, factory, registry, properties));
    }

    private static Map<String, String> queues() {
        Map<String, String> queues = new LinkedHashMap<>();
        for (String kind : java.util.List.of("workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm")) {
            queues.put(kind, "ai-factory-" + kind);
        }
        return queues;
    }

    private record Fixture(WorkerFactory factory, WorkflowServiceGrpc.WorkflowServiceBlockingStub stub,
                           TemporalEngineHealthIndicator indicator) {}
}
