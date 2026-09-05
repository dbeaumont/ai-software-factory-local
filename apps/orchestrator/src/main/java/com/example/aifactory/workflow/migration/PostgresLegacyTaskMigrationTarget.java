package com.example.aifactory.workflow.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Durable, idempotent import that deliberately creates no Temporal workflow or workflow-run row. */
@Repository
public final class PostgresLegacyTaskMigrationTarget implements LegacyTaskMigrationTarget {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresLegacyTaskMigrationTarget(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void importAtomically(List<TaskRecord> tasks) {
        if (tasks == null) throw new IllegalArgumentException("Legacy task import batch is required");
        transactions.executeWithoutResult(ignored -> tasks.forEach(this::importOne));
    }

    private void importOne(TaskRecord task) {
        require(task);
        List<TaskRecord> existing = find(task.taskId());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().equals(task)) {
                throw new IllegalStateException("Divergent legacy task re-import for " + task.taskId());
            }
            return;
        }
        jdbc.update("INSERT INTO tasks(task_id, ticket_number, repository_id, current_attempt_id, source_commit, "
                        + "requirement_digest, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                task.taskId(), task.ticketNumber(), task.repositoryId(), task.attemptId(), task.sourceCommit(),
                task.requirementDigest(), task.targetStatus(), task.createdAt(), task.updatedAt());
        jdbc.update("INSERT INTO legacy_task_imports(task_id, attempt_id, source_commit, source_commit_verified, "
                        + "legacy_status, snapshot_uri, snapshot_digest, snapshot_classification, migrated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                task.taskId(), task.attemptId(), task.sourceCommit(), task.sourceCommitVerified(),
                task.legacyStatus(), task.snapshotUri(), task.snapshotDigest(), task.snapshotClassification());
    }

    private List<TaskRecord> find(String taskId) {
        return jdbc.query("SELECT t.task_id, t.ticket_number, t.repository_id, l.attempt_id, l.source_commit, "
                        + "l.source_commit_verified, t.requirement_digest, t.status, l.legacy_status, t.created_at, "
                        + "t.updated_at, l.snapshot_uri, l.snapshot_digest, l.snapshot_classification "
                        + "FROM legacy_task_imports l JOIN tasks t ON t.task_id = l.task_id WHERE l.task_id = ?",
                (row, index) -> new TaskRecord(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), row.getBoolean(6), row.getString(7), row.getString(8),
                        row.getString(9), row.getObject(10, java.time.OffsetDateTime.class).toInstant(),
                        row.getObject(11, java.time.OffsetDateTime.class).toInstant(), row.getString(12),
                        row.getString(13), row.getString(14)), taskId);
    }

    private static void require(TaskRecord task) {
        if (task == null || task.taskId() == null || !task.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || task.ticketNumber() == null || !task.ticketNumber().matches("AF-[0-9]{4,}")
                || !List.of("PR_CREATED", "FAILED").contains(task.legacyStatus())
                || !List.of("COMPLETED", "FAILED").contains(task.targetStatus())
                || task.snapshotUri() == null || !task.snapshotUri().startsWith("evidence://")
                || task.snapshotDigest() == null || !task.snapshotDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Legacy task import metadata is invalid");
        }
    }
}
