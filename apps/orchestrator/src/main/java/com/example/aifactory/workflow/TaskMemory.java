package com.example.aifactory.workflow;

import com.example.aifactory.model.TaskState;

import java.util.List;
import java.util.Optional;

/** Persistence-neutral port for workflow task state. */
public interface TaskMemory {
    void save(TaskState state);

    Optional<TaskState> find(String taskId);

    List<TaskState> list();
}
