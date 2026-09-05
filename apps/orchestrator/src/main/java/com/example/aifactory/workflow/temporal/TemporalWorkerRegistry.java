package com.example.aifactory.workflow.temporal;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Owns the complete, immutable set of production workers before their registrations and startup. */
public final class TemporalWorkerRegistry {
    private static final Set<String> REQUIRED = Set.of(
            "workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm");
    private final Map<String, Worker> workers;
    private final Map<String, String> taskQueues;

    public TemporalWorkerRegistry(WorkerFactory factory, Map<String, String> taskQueues,
                                  String deploymentName, String buildId) {
        if (factory == null || taskQueues == null || !taskQueues.keySet().containsAll(REQUIRED)
                || taskQueues.values().stream().distinct().count() != taskQueues.size()
                || deploymentName == null || buildId == null) {
            throw new IllegalArgumentException("Temporal worker topology is incomplete or ambiguous");
        }
        WorkerDeploymentOptions deployment = WorkerDeploymentOptions.newBuilder()
                .setUseVersioning(true)
                .setVersion(new WorkerDeploymentVersion(deploymentName, buildId))
                .setDefaultVersioningBehavior(VersioningBehavior.PINNED)
                .build();
        WorkerOptions options = WorkerOptions.newBuilder().setDeploymentOptions(deployment).build();
        Map<String, Worker> registered = new LinkedHashMap<>();
        REQUIRED.stream().sorted().forEach(kind -> registered.put(kind,
                factory.newWorker(taskQueues.get(kind), options)));
        registered.get("workflow").registerWorkflowImplementationTypes(
                SoftwareFactoryExecutionWorkflowV1Impl.class);
        this.workers = Map.copyOf(registered);
        this.taskQueues = Map.copyOf(taskQueues);
    }

    public Worker worker(String kind) {
        Worker worker = workers.get(kind);
        if (worker == null) throw new IllegalArgumentException("Unknown Temporal worker kind: " + kind);
        return worker;
    }

    public Map<String, Worker> workers() {
        return workers;
    }

    public Map<String, String> taskQueues() {
        return taskQueues;
    }
}
