package com.example.aifactory.workflow.projection;

import java.time.Instant;
import java.util.List;

/** Metadata-only snapshot. Evidence payloads must never be copied into PostgreSQL. */
public record UiProjectionSnapshot(Task task, WorkflowRun workflowRun, List<Delegation> delegations,
                                   List<Evidence> evidence) {
    public UiProjectionSnapshot {
        delegations = List.copyOf(delegations);
        evidence = List.copyOf(evidence);
    }

    public record Task(String taskId, String repositoryId, String attemptId, String sourceCommit,
                       String requirementDigest, String status, Instant createdAt, Instant updatedAt) {}

    public record WorkflowRun(String workflowRunId, String workflowId, String temporalRunId,
                              String taskId, String attemptId, String sourceCommit, String status,
                              Instant startedAt, Instant completedAt) {}

    public record Delegation(String delegationId, String parentDelegationId, String workflowRunId,
                             String taskId, String attemptId, String sourceCommit, String role, String status,
                             long budgetTokens, long budgetCostMicros, int budgetTurns) {}

    public record Evidence(String artifactId, String evidenceRefId, String workflowRunId, String taskId,
                           String attemptId, String sourceCommit, String type, String uri, String digest,
                           String status, String classification, long sizeBytes) {}
}
