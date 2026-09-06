package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.IndependentReviewBundle;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.SignalMethod;

/** Dedicated workflow boundary that cannot be scheduled as a Supervisor delegation. */
@WorkflowInterface
public interface IndependentReviewWorkflow {
    @WorkflowMethod(name = "IndependentReviewWorkflow")
    Result run(Request request);

    @SignalMethod(name = "a2aTaskUpdate")
    default void a2aTaskUpdate(com.example.aifactory.a2a.A2aContracts.Notification notification) { }

    record Request(String taskId, String attemptId, String reviewId, String sourceCommit,
                   IndependentReviewBundle bundle, DelegationWorkflow.Budget budget) {
        public Request {
            budget = budget == null ? new DelegationWorkflow.Budget(10_000, 10_000_000, 6) : budget;
        }
    }

    record Result(String reviewId, String role, String status) {}
}
