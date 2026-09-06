package com.example.aifactory.workflow.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Atomically replaces the metadata-only relational projection after external authorities were verified. */
@Repository
public class PostgresUiProjectionStore implements UiProjectionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresUiProjectionStore(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public void replaceAtomically(UiProjectionSnapshot snapshot) {
        requireConsistent(snapshot);
        transactions.executeWithoutResult(ignored -> replace(snapshot));
    }

    private void replace(UiProjectionSnapshot snapshot) {
        UiProjectionSnapshot.Task task = snapshot.task();
        UiProjectionSnapshot.WorkflowRun run = snapshot.workflowRun();
        for (String table : new String[]{"tool_invocations", "budget_usage", "agent_runs", "contradictions",
                "evidence_refs", "artifacts", "delegations"}) {
            jdbc.update("DELETE FROM " + table + " WHERE task_id = ?", task.taskId());
        }
        if (count("tasks", "task_id", task.taskId()) == 0) {
            jdbc.update("INSERT INTO tasks(task_id, ticket_number, repository_id, current_attempt_id, source_commit, "
                            + "requirement_digest, status, created_at, updated_at, version) "
                            + "VALUES (?, 'AF-' || to_char(nextval('task_ticket_number_seq'), 'FM0000'), "
                            + "?, ?, ?, ?, ?, ?, ?, 0)",
                    task.taskId(), task.repositoryId(), task.attemptId(), task.sourceCommit(),
                    task.requirementDigest(), task.status(), sqlTime(task.createdAt()), sqlTime(task.updatedAt()));
        } else {
            jdbc.update("UPDATE tasks SET repository_id = ?, current_attempt_id = ?, source_commit = ?, "
                            + "requirement_digest = ?, status = ?, updated_at = ?, version = version + 1 "
                            + "WHERE task_id = ?", task.repositoryId(), task.attemptId(), task.sourceCommit(),
                    task.requirementDigest(), task.status(), sqlTime(task.updatedAt()), task.taskId());
        }
        if (count("workflow_runs", "workflow_run_id", run.workflowRunId()) == 0) {
            jdbc.update("INSERT INTO workflow_runs(workflow_run_id, workflow_id, temporal_run_id, task_id, "
                            + "attempt_id, source_commit, status, started_at, completed_at, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    run.workflowRunId(), run.workflowId(), UUID.fromString(run.temporalRunId()), run.taskId(),
                    run.attemptId(), run.sourceCommit(), run.status(), sqlTime(run.startedAt()),
                    sqlTime(run.completedAt()), sqlTime(Instant.now()));
        } else {
            jdbc.update("UPDATE workflow_runs SET source_commit = ?, status = ?, completed_at = ?, updated_at = ?, "
                            + "version = version + 1 WHERE workflow_run_id = ?",
                    run.sourceCommit(), run.status(), sqlTime(run.completedAt()), sqlTime(Instant.now()),
                    run.workflowRunId());
        }

        Set<String> delegationIds = new HashSet<>();
        snapshot.delegations().forEach(value -> delegationIds.add(value.delegationId()));
        for (UiProjectionSnapshot.Delegation value : snapshot.delegations()) {
            String parent = delegationIds.contains(value.parentDelegationId()) ? value.parentDelegationId() : null;
            jdbc.update("INSERT INTO delegations(delegation_id, parent_delegation_id, workflow_run_id, task_id, "
                            + "attempt_id, source_commit, role, objective_digest, budget_tokens, budget_cost_micros, "
                            + "budget_turns, status, created_at, completed_at, updated_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    value.delegationId(), parent, value.workflowRunId(), value.taskId(), value.attemptId(),
                    value.sourceCommit(), value.role(), sha256(value.role() + ':' + value.delegationId()),
                    value.budgetTokens(), value.budgetCostMicros(), value.budgetTurns(), value.status(),
                    sqlTime(run.startedAt()), sqlTime(run.completedAt()), sqlTime(Instant.now()));
        }
        for (UiProjectionSnapshot.Evidence value : snapshot.evidence()) {
            Instant now = Instant.now();
            jdbc.update("INSERT INTO artifacts(artifact_id, task_id, attempt_id, source_commit, artifact_type, "
                            + "evidence_uri, digest, status, classification, trust_domain, media_type, size_bytes, "
                            + "created_at, retain_until, version, information_kind) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'EVIDENCE_MCP', 'application/octet-stream', ?, ?, ?, 0, "
                            + "'VERIFIED_EVIDENCE')",
                    value.artifactId(), value.taskId(), value.attemptId(), value.sourceCommit(), value.type(),
                    value.uri(), value.digest(), value.status(), value.classification(), value.sizeBytes(),
                    sqlTime(now), sqlTime(now.plus(Duration.ofDays(30))));
            jdbc.update("INSERT INTO evidence_refs(evidence_ref_id, artifact_id, workflow_run_id, task_id, "
                            + "attempt_id, source_commit, purpose, verification, verified_at, created_at, version, "
                            + "information_kind) VALUES (?, ?, ?, ?, ?, ?, 'PROJECTION_REBUILD', 'VERIFIED', ?, ?, 0, "
                            + "'VERIFIED_EVIDENCE')",
                    value.evidenceRefId(), value.artifactId(), value.workflowRunId(), value.taskId(),
                    value.attemptId(), value.sourceCommit(), sqlTime(now), sqlTime(now));
        }
    }

    private static void requireConsistent(UiProjectionSnapshot snapshot) {
        if (snapshot == null || snapshot.task() == null || snapshot.workflowRun() == null) {
            throw new IllegalArgumentException("Projection rebuild snapshot is incomplete");
        }
        UiProjectionSnapshot.Task task = snapshot.task();
        UiProjectionSnapshot.WorkflowRun run = snapshot.workflowRun();
        if (!task.taskId().equals(run.taskId()) || !task.attemptId().equals(run.attemptId())
                || !task.sourceCommit().equals(run.sourceCommit())
                || !task.sourceCommit().matches("[0-9a-f]{40}")) {
            throw new SecurityException("Projection rebuild lineage diverged");
        }
        snapshot.delegations().forEach(value -> requireLineage(task, value.taskId(), value.attemptId(),
                value.sourceCommit()));
        snapshot.evidence().forEach(value -> requireLineage(task, value.taskId(), value.attemptId(),
                value.sourceCommit()));
    }

    private int count(String table, String key, String value) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + key + " = ?",
                Integer.class, value);
        return count == null ? 0 : count;
    }

    private static void requireLineage(UiProjectionSnapshot.Task task, String taskId, String attemptId,
                                       String sourceCommit) {
        if (!task.taskId().equals(taskId) || !task.attemptId().equals(attemptId)
                || !task.sourceCommit().equals(sourceCommit)) {
            throw new SecurityException("Projection rebuild child lineage diverged");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static java.time.OffsetDateTime sqlTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
