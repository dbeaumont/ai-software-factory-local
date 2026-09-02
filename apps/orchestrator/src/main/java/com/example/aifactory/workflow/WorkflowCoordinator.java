package com.example.aifactory.workflow;

import com.example.aifactory.model.TaskState;

/**
 * Application port for starting and resuming a software-factory workflow.
 *
 * <p>The port deliberately exposes no Temporal type. Implementations may execute the legacy deterministic
 * pipeline, start a durable workflow, or select one according to a host-owned routing policy.</p>
 */
public interface WorkflowCoordinator {
    void start(TaskState task);

    void resumeAfterApproval(TaskState task);
}
