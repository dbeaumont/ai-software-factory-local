package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Dedicated workflow boundary that cannot be scheduled as a Supervisor delegation. */
@WorkflowInterface
public interface IndependentReviewWorkflow {
    @WorkflowMethod(name = "IndependentReviewWorkflow")
    Result run(Request request);

    record Request(String taskId, String attemptId, String reviewId, String sourceCommit,
                   String objective, DelegationWorkflow.Budget budget) {
        public Request {
            budget = budget == null ? new DelegationWorkflow.Budget(10_000, 10_000_000, 6) : budget;
        }
    }

    record Result(String reviewId, String role, String status) {}
}
