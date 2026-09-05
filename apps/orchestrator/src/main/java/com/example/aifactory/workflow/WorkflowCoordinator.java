package com.example.aifactory.workflow;

import com.example.aifactory.model.TaskState;

/**
 * Application port for starting and resuming a software-factory workflow.
 *
 * <p>The port deliberately exposes no Temporal type. Its production implementation starts and signals the
 * durable workflow; callers remain independent from the Temporal SDK.</p>
 */
public interface WorkflowCoordinator {
    void start(TaskState task);

    void resumeAfterApproval(TaskState task);
}
