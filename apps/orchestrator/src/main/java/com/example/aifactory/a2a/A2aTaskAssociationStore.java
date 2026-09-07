package com.example.aifactory.a2a;

import java.util.Optional;

/** Projection of server-generated A2A identifiers keyed by the business delegation identity. */
public interface A2aTaskAssociationStore {
    void prepareDelegation(A2aExecutionContext execution, DispatchIntent intent);
    void record(A2aExecutionContext execution, String messageId, String agentCardDigest,
                String a2aTaskId, String a2aContextId);
    Optional<Association> findByDelegation(String delegationId);
    Optional<Association> findByMessageId(String agentRole, String messageId);
    Optional<Association> findByA2aTaskId(String agentRole, String a2aTaskId);

    record Association(String delegationId, String taskId, String attemptId, String workflowId,
                       String workflowRunId, String sourceCommit, String messageId, String agentRole,
                       String agentCardDigest, String a2aTaskId, String a2aContextId) {}

    /** Immutable business data that must exist before the remote A2A side effect starts. */
    record DispatchIntent(String objectiveDigest, long budgetTokens, long budgetCostMicros, int budgetTurns) {
        public DispatchIntent {
            if (objectiveDigest == null || !objectiveDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("A2A delegation objective digest is invalid");
            }
            if (budgetTokens <= 0 || budgetCostMicros < 0 || budgetTurns <= 0) {
                throw new IllegalArgumentException("A2A delegation budget is invalid");
            }
        }
    }
}
