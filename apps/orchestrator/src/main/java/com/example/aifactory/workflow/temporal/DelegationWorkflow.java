package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.SignalMethod;

import java.util.Set;

@WorkflowInterface
public interface DelegationWorkflow {
    @WorkflowMethod(name = "DelegationWorkflow")
    Result run(Request request);

    @SignalMethod(name = "a2aTaskUpdate")
    default void a2aTaskUpdate(com.example.aifactory.a2a.A2aContracts.Notification notification) { }

    record Request(String taskId, String attemptId, String nodeId, String parentNodeId,
                   String role, String sourceCommit, String objectiveDigest, int priority,
                   Set<String> dependsOn, Budget budget) {
        private static final int DEFAULT_PRIORITY = 100;

        public Request {
            if (priority < 0) throw new IllegalArgumentException("Delegation priority is invalid");
            if (objectiveDigest == null || objectiveDigest.isBlank()) {
                throw new IllegalArgumentException("Delegation objective is required");
            }
            objectiveDigest = objectiveDigest.matches("[0-9a-f]{64}")
                    ? objectiveDigest : TemporalIds.sha256(objectiveDigest);
            dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        }

        public Request(String taskId, String attemptId, String nodeId, String parentNodeId,
                       String role, String sourceCommit, String objective, Set<String> dependsOn, Budget budget) {
            this(taskId, attemptId, nodeId, parentNodeId, role, sourceCommit, objective,
                    DEFAULT_PRIORITY, dependsOn, budget);
        }

        public Request(String taskId, String attemptId, String nodeId, String parentNodeId,
                       String role, String sourceCommit, String objective, Budget budget) {
            this(taskId, attemptId, nodeId, parentNodeId, role, sourceCommit, objective,
                    DEFAULT_PRIORITY, Set.of(), budget);
        }

        public Request(String taskId, String attemptId, String nodeId, String parentNodeId,
                       String role, String sourceCommit, String objective) {
            this(taskId, attemptId, nodeId, parentNodeId, role, sourceCommit, objective,
                    DEFAULT_PRIORITY, Set.of(),
                    new Budget(1_000, 1_000_000, 6));
        }
    }

    record Budget(long maxTokens, long maxCostMicros, int maxTurns, long timeoutSeconds) {
        public Budget(long maxTokens, long maxCostMicros, int maxTurns) {
            this(maxTokens, maxCostMicros, maxTurns, 600);
        }

        public Budget {
            if (maxTokens < 1 || maxCostMicros < 0 || maxTurns < 1 || timeoutSeconds < 1) {
                throw new IllegalArgumentException("Delegation budget is invalid");
            }
        }
    }

    record Result(String nodeId, String role, String status) {}
}
