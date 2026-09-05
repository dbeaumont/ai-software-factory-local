package com.example.aifactory.workflow;

import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.HumanDecisionResponse;
import com.example.aifactory.model.TaskCancellationRequest;
import com.example.aifactory.model.OperatorActionRequest;

/**
 * Application port for starting and resuming a software-factory workflow.
 *
 * <p>The port deliberately exposes no Temporal type. Its production implementation starts and signals the
 * durable workflow; callers remain independent from the Temporal SDK.</p>
 */
public interface WorkflowCoordinator {
    void start(TaskState task);

    void resumeAfterApproval(TaskState task);

    default void answerHumanDecision(TaskState task, String requestId, HumanDecisionResponse response) {
        throw new UnsupportedOperationException("Human decisions require a durable workflow coordinator");
    }

    default void cancel(TaskState task, TaskCancellationRequest request) {
        throw new UnsupportedOperationException("Cancellation requires a durable workflow coordinator");
    }

    default void retry(TaskState task, String delegationId, OperatorActionRequest request) {
        throw new UnsupportedOperationException("Retry requires a durable workflow coordinator");
    }
}
