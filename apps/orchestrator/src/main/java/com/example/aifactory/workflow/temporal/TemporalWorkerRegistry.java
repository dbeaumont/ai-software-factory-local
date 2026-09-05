package com.example.aifactory.workflow.temporal;

import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Owns the complete, immutable set of production workers before their registrations and startup. */
public final class TemporalWorkerRegistry {
    private static final Set<String> REQUIRED = Set.of(
            "workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm");
    private final Map<String, Worker> workers;
    private final Map<String, String> taskQueues;

    public TemporalWorkerRegistry(WorkerFactory factory, Map<String, String> taskQueues) {
        if (factory == null || taskQueues == null || !taskQueues.keySet().containsAll(REQUIRED)
                || taskQueues.values().stream().distinct().count() != taskQueues.size()) {
            throw new IllegalArgumentException("Temporal worker topology is incomplete or ambiguous");
        }
        Map<String, Worker> registered = new LinkedHashMap<>();
        REQUIRED.stream().sorted().forEach(kind -> registered.put(kind, factory.newWorker(taskQueues.get(kind))));
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
