package com.example.aifactory.workflow.migration;

import java.time.Instant;
import java.util.List;

/** Atomic import boundary implemented by the durable PostgreSQL task-memory adapter. */
public interface LegacyTaskMigrationTarget {
    /** The batch must be idempotent by task id and reject any divergent re-import. */
    void importAtomically(List<TaskRecord> tasks);

    record TaskRecord(String taskId, String repositoryId, String attemptId, String sourceCommit,
                      boolean sourceCommitVerified, String requirementDigest, String targetStatus,
                      String legacyStatus, Instant createdAt, Instant updatedAt, String snapshotUri,
                      String snapshotDigest, String snapshotClassification) {}
}
