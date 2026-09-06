package com.example.aifactory.a2a;

import java.util.Optional;

/** Projection of server-generated A2A identifiers keyed by the business delegation identity. */
public interface A2aTaskAssociationStore {
    void record(A2aExecutionContext execution, String messageId, String agentCardDigest,
                String a2aTaskId, String a2aContextId);
    Optional<Association> findByDelegation(String delegationId);
    Optional<Association> findByMessageId(String agentRole, String messageId);
    Optional<Association> findByA2aTaskId(String agentRole, String a2aTaskId);

    record Association(String delegationId, String taskId, String attemptId, String workflowId,
                       String workflowRunId, String sourceCommit, String messageId, String agentRole,
                       String agentCardDigest, String a2aTaskId, String a2aContextId) {}
}
