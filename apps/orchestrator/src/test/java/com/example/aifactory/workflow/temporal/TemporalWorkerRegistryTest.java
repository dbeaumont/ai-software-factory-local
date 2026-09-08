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
                SoftwareFactoryExecutionWorkflowV2Impl.class,
                A2aDelegationWorkflowImpl.class,
                PatchIntegrationWorkflowImpl.class,
                A2aIndependentReviewWorkflowImpl.class);
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
        PatchIntegrationActivities patch = mock(PatchIntegrationActivities.class);
        SourceResolutionActivities source = mock(SourceResolutionActivities.class);
        PipelineExecutionActivities pipeline = mock(PipelineExecutionActivities.class);
        A2aActivitiesImpl a2a = mock(A2aActivitiesImpl.class);
        HierarchicalRoutingActivities routing = mock(HierarchicalRoutingActivities.class);
        HierarchicalExecutionActivities hierarchical = mock(HierarchicalExecutionActivities.class);
        TemporalActivityAdapters adapters = new TemporalActivityAdapters(
                patch, source, pipeline, a2a, routing, hierarchical);

        assertThat(adapters.forWorker("context")).hasSize(3)
                .anyMatch(SourceResolutionActivities.class::isInstance)
                .anyMatch(HierarchicalRoutingActivities.class::isInstance);
        assertThat(adapters.forWorker("llm")).hasSize(1)
                .anyMatch(PipelineExecutionActivities.class::isInstance);
        assertThat(adapters.forWorker("sandbox")).hasSize(2)
                .anyMatch(PatchIntegrationActivities.class::isInstance);
        assertThat(adapters.forWorker("assurance")).hasSize(1)
                .anyMatch(PipelineExecutionActivities.class::isInstance);
        assertThat(adapters.forWorker("evidence")).hasSize(2)
                .anyMatch(PipelineExecutionActivities.class::isInstance)
                .anyMatch(HierarchicalExecutionActivities.class::isInstance);
        assertThat(adapters.forWorker("scm")).hasSize(1)
                .anyMatch(PipelineExecutionActivities.class::isInstance);
        assertThat(adapters.forWorker("llm")).noneMatch(DurableExecutionActivities.class::isInstance);
        assertThat(adapters.forWorker("workflow")).containsExactly(a2a);
    }

    @Test
    void keepsOldAndNewCompatibleBuildIdsRegisteredOnEveryQueueAtTheSameTime() {
        WorkerFactory oldFactory = mock(WorkerFactory.class);
        WorkerFactory newFactory = mock(WorkerFactory.class);
        when(oldFactory.newWorker(anyString(), any(WorkerOptions.class)))
                .thenAnswer(ignored -> mock(Worker.class));
        when(newFactory.newWorker(anyString(), any(WorkerOptions.class)))
                .thenAnswer(ignored -> mock(Worker.class));

        TemporalWorkerRegistry oldWorkers = new TemporalWorkerRegistry(
                oldFactory, queues(), "ai-factory-orchestrator", "build-old");
        TemporalWorkerRegistry newWorkers = new TemporalWorkerRegistry(
                newFactory, queues(), "ai-factory-orchestrator", "build-new");

        assertThat(oldWorkers.taskQueues()).isEqualTo(newWorkers.taskQueues());
        ArgumentCaptor<WorkerOptions> oldOptions = ArgumentCaptor.forClass(WorkerOptions.class);
        ArgumentCaptor<WorkerOptions> newOptions = ArgumentCaptor.forClass(WorkerOptions.class);
        verify(oldFactory, times(7)).newWorker(anyString(), oldOptions.capture());
        verify(newFactory, times(7)).newWorker(anyString(), newOptions.capture());
        assertThat(oldOptions.getAllValues()).allSatisfy(options -> {
            assertThat(options.getDeploymentOptions().getVersion().getDeploymentName())
                    .isEqualTo("ai-factory-orchestrator");
            assertThat(options.getDeploymentOptions().getVersion().getBuildId()).isEqualTo("build-old");
            assertThat(options.getDeploymentOptions().getDefaultVersioningBehavior())
                    .isEqualTo(io.temporal.common.VersioningBehavior.PINNED);
        });
        assertThat(newOptions.getAllValues()).allSatisfy(options -> {
            assertThat(options.getDeploymentOptions().getVersion().getDeploymentName())
                    .isEqualTo("ai-factory-orchestrator");
            assertThat(options.getDeploymentOptions().getVersion().getBuildId()).isEqualTo("build-new");
            assertThat(options.getDeploymentOptions().getDefaultVersioningBehavior())
                    .isEqualTo(io.temporal.common.VersioningBehavior.PINNED);
        });
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
