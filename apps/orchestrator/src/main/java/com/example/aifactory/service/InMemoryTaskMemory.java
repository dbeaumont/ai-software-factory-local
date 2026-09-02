package com.example.aifactory.service;

import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.TaskMemory;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Local prototype adapter; durable storage can replace it without changing agents or task commands. */
@Repository
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
}
