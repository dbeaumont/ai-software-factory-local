package com.example.aifactory.workflow.temporal;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class TemporalWorkerRegistryTest {
    @Test
    void createsEveryRequiredWorkerExactlyOnItsConfiguredQueue() {
        WorkerFactory factory = mock(WorkerFactory.class);
        Map<String, String> queues = queues();
        Map<String, Worker> workers = new LinkedHashMap<>();
        queues.forEach((kind, queue) -> {
            Worker worker = mock(Worker.class);
            workers.put(kind, worker);
            when(factory.newWorker(eq(queue), any(WorkerOptions.class))).thenReturn(worker);
        });

        TemporalWorkerRegistry registry = new TemporalWorkerRegistry(
                factory, queues, "ai-factory-orchestrator", "0.1.0");

        assertThat(registry.workers()).containsOnlyKeys(
                "workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm");
        queues.forEach((kind, queue) -> {
            assertThat(registry.worker(kind)).isNotNull();
            verify(factory).newWorker(eq(queue), any(WorkerOptions.class));
        });
        verify(workers.get("workflow")).registerWorkflowImplementationTypes(
                SoftwareFactoryExecutionWorkflowV1Impl.class,
                DelegationWorkflowImpl.class,
                PatchIntegrationWorkflowImpl.class,
                IndependentReviewWorkflowImpl.class);
        ArgumentCaptor<WorkerOptions> options = ArgumentCaptor.forClass(WorkerOptions.class);
        verify(factory, times(7)).newWorker(anyString(), options.capture());
        assertThat(options.getAllValues()).allSatisfy(value -> {
            assertThat(value.getDeploymentOptions().isUsingVersioning()).isTrue();
            assertThat(value.getDeploymentOptions().getVersion().getDeploymentName())
                    .isEqualTo("ai-factory-orchestrator");
            assertThat(value.getDeploymentOptions().getVersion().getBuildId()).isEqualTo("0.1.0");
            assertThat(value.getDeploymentOptions().getDefaultVersioningBehavior())
                    .isEqualTo(io.temporal.common.VersioningBehavior.PINNED);
            assertThat(value.getMaxConcurrentWorkflowTaskPollers()).isEqualTo(2);
            assertThat(value.getMaxConcurrentActivityTaskPollers()).isEqualTo(2);
            assertThat(value.getMaxConcurrentWorkflowTaskExecutionSize()).isEqualTo(4);
            assertThat(value.getMaxConcurrentActivityExecutionSize()).isEqualTo(4);
            assertThat(value.getMaxTaskQueueActivitiesPerSecond()).isEqualTo(10.0);
            assertThat(value.getStickyTaskQueueDrainTimeout()).isEqualTo(java.time.Duration.ofSeconds(10));
        });
    }

    @Test
    void exposesOnlyTheActivitiesAssignedToEachSpecializedWorker() {
        DurableExecutionActivities durable = mock(DurableExecutionActivities.class);
        PatchIntegrationActivities patch = mock(PatchIntegrationActivities.class);
        SourceResolutionActivities source = mock(SourceResolutionActivities.class);
        TemporalActivityAdapters adapters = new TemporalActivityAdapters(durable, patch, source);

        assertThat(adapters.forWorker("context")).hasSize(2)
                .anyMatch(TemporalActivityAdapters.ContextActivities.class::isInstance)
                .anyMatch(SourceResolutionActivities.class::isInstance);
        assertThat(adapters.forWorker("llm")).hasSize(1)
                .allMatch(TemporalActivityAdapters.LlmActivities.class::isInstance);
        assertThat(adapters.forWorker("sandbox")).hasSize(2)
                .anyMatch(TemporalActivityAdapters.SandboxActivities.class::isInstance)
                .anyMatch(PatchIntegrationActivities.class::isInstance);
        assertThat(adapters.forWorker("assurance")).hasSize(1)
                .allMatch(TemporalActivityAdapters.AssuranceActivities.class::isInstance);
        assertThat(adapters.forWorker("evidence")).hasSize(1)
                .allMatch(TemporalActivityAdapters.EvidenceActivities.class::isInstance);
        assertThat(adapters.forWorker("scm")).hasSize(1)
                .allMatch(TemporalActivityAdapters.ScmActivities.class::isInstance);
        assertThatThrownBy(() -> adapters.forWorker("workflow"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingOrAliasedTaskQueues() {
        Map<String, String> missing = queues();
        missing.remove("scm");
        assertThatThrownBy(() -> new TemporalWorkerRegistry(
                mock(WorkerFactory.class), missing, "deployment", "build"))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, String> duplicate = queues();
        duplicate.put("scm", duplicate.get("evidence"));
        assertThatThrownBy(() -> new TemporalWorkerRegistry(
                mock(WorkerFactory.class), duplicate, "deployment", "build"))
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
