package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DelegationWorkflow {
    @WorkflowMethod(name = "DelegationWorkflow")
    Result run(Request request);

    record Request(String taskId, String attemptId, String nodeId, String parentNodeId,
                   String role, String sourceCommit, String objective, Budget budget) {
        public Request(String taskId, String attemptId, String nodeId, String parentNodeId,
                       String role, String sourceCommit, String objective) {
            this(taskId, attemptId, nodeId, parentNodeId, role, sourceCommit, objective,
                    new Budget(1_000, 1_000_000, 6));
        }
    }

    record Budget(long maxTokens, long maxCostMicros, int maxTurns) {
        public Budget {
            if (maxTokens < 1 || maxCostMicros < 0 || maxTurns < 1) {
                throw new IllegalArgumentException("Delegation budget is invalid");
            }
        }
    }

    record Result(String nodeId, String role, String status) {}
}
