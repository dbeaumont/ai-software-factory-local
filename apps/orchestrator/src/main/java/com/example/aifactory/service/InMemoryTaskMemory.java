package com.example.aifactory.service;

import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.TaskMemory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local prototype adapter; durable storage can replace it without changing agents or task commands. */
public final class InMemoryTaskMemory implements TaskMemory {
    private final ConcurrentMap<String, TaskState> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(TaskState state) {
        tasks.put(state.id, state);
    }

    @Override
    public Optional<TaskState> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<TaskState> list() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(state -> state.createdAt))
                .toList();
    }

    @Override
    public Optional<ProjectionStatus> projectionStatus(String taskId) {
        return find(taskId).map(state -> {
            long age = Math.max(0, Duration.between(state.updatedAt, Instant.now()).toMillis());
            return new ProjectionStatus(state.id, state.workflowAttemptId, state.projectionVersion, null,
                    state.updatedAt, age, age > Duration.ofSeconds(30).toMillis());
        });
    }
}
