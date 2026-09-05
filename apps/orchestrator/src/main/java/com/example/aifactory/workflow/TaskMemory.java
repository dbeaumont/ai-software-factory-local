package com.example.aifactory.workflow;

import com.example.aifactory.model.TaskState;

import java.util.List;
import java.util.Optional;

/** Persistence-neutral port for workflow task state. */
public interface TaskMemory {
    void save(TaskState state);

    /** Atomically records a task and the durable intent to start its Temporal workflow. */
    default void admit(TaskState state) {
        save(state);
    }

    /** Atomically persists the Temporal run identity and closes the corresponding admission intent. */
    default void workflowStarted(TaskState state) {
        save(state);
    }

    /** Returns a bounded set of admissions whose deterministic Temporal workflow still needs starting. */
    default List<TaskState> pendingAdmissions(int limit) {
        return List.of();
    }

    /** Defers a failed admission retry without persisting exception messages or request content. */
    default void admissionFailed(TaskState state, RuntimeException failure) {
        // Volatile adapters have no durable reconciliation queue.
    }

    Optional<TaskState> find(String taskId);

    List<TaskState> list();
}
