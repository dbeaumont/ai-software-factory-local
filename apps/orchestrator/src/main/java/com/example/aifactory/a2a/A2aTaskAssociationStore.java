package com.example.aifactory.a2a;

import java.util.Optional;

/** Projection of server-generated A2A identifiers keyed by the business delegation identity. */
public interface A2aTaskAssociationStore {
    void record(A2aExecutionContext execution, String a2aTaskId, String a2aContextId);
    Optional<Association> findByDelegation(String delegationId);

    record Association(String delegationId, String taskId, String attemptId, String sourceCommit,
                       String agentRole, String a2aTaskId, String a2aContextId) {}
}
