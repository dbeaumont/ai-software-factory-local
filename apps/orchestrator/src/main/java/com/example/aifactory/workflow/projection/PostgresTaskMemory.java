package com.example.aifactory.workflow.projection;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.service.ScmDeliveryGateway;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.TaskMemory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Transactional metadata projection whose complete payload is encrypted and retained by Evidence MCP. */
@Repository
public final class PostgresTaskMemory implements TaskMemory {
    private static final String UNRESOLVED_COMMIT = "0".repeat(40);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EvidenceRepository evidence;
    private final ObjectMapper mapper;

    public PostgresTaskMemory(JdbcTemplate jdbc, TransactionTemplate transactions,
                              EvidenceRepository evidence, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.evidence = evidence;
        this.mapper = mapper;
    }

    @Override
    public void save(TaskState state) {
        if (state == null) throw new IllegalArgumentException("Task state is required");
        ProjectionSnapshot payload = new ProjectionSnapshot(
                state.view(), state.workflowAttemptId, state.approvalExpiresAt);
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("Task projection cannot be encoded", failure);
        }
        String digest = sha256(bytes);
        EvidenceRepository.StoredEvidence snapshot = evidence.store(new EvidenceRepository.StoreRequest(
                state.id, state.workflowAttemptId, "metadata", "application/json", bytes, digest, "workflow"));
        transactions.executeWithoutResult(ignored -> persistMetadata(state, snapshot));
    }

    private void persistMetadata(TaskState state, EvidenceRepository.StoredEvidence snapshot) {
        List<Long> versions = jdbc.query("SELECT version FROM task_projection_snapshots WHERE task_id = ? FOR UPDATE",
                (row, index) -> row.getLong(1), state.id);
        String sourceCommit = state.sourceCommit == null ? UNRESOLVED_COMMIT : state.sourceCommit;
        String requirementDigest = sha256(state.request.requirement().getBytes(StandardCharsets.UTF_8));
        if (versions.isEmpty()) {
            try {
                jdbc.update("INSERT INTO tasks(task_id, repository_id, current_attempt_id, source_commit, "
                                + "requirement_digest, status, created_at, updated_at, version) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                        state.id, ScmDeliveryGateway.repositoryId(state.request.repositoryUrl()),
                        state.workflowAttemptId, sourceCommit, requirementDigest, state.status.name(),
                        state.createdAt, state.updatedAt);
                jdbc.update("INSERT INTO task_projection_snapshots(task_id, attempt_id, snapshot_uri, "
                                + "snapshot_digest, projected_at, version) VALUES (?, ?, ?, ?, ?, 0)",
                        state.id, state.workflowAttemptId, snapshot.uri(), snapshot.digest(), Instant.now());
                state.projectionVersion = 0;
                return;
            } catch (DuplicateKeyException concurrentInsert) {
                throw new OptimisticProjectionLockException(state.id, state.projectionVersion, concurrentInsert);
            }
        }
        long storedVersion = versions.getFirst();
        if (storedVersion != state.projectionVersion) {
            throw new OptimisticProjectionLockException(state.id, state.projectionVersion, null);
        }
        jdbc.update("UPDATE tasks SET current_attempt_id = ?, source_commit = ?, status = ?, updated_at = ?, "
                        + "version = version + 1 WHERE task_id = ?",
                state.workflowAttemptId, sourceCommit, state.status.name(), state.updatedAt, state.id);
        int updated = jdbc.update("UPDATE task_projection_snapshots SET attempt_id = ?, snapshot_uri = ?, "
                        + "snapshot_digest = ?, projected_at = ?, version = version + 1 "
                        + "WHERE task_id = ? AND version = ?",
                state.workflowAttemptId, snapshot.uri(), snapshot.digest(), Instant.now(), state.id, storedVersion);
        if (updated != 1) throw new OptimisticProjectionLockException(state.id, storedVersion, null);
        state.projectionVersion = storedVersion + 1;
    }

    @Override
    public Optional<TaskState> find(String taskId) {
        List<ProjectionReference> references = jdbc.query(
                "SELECT attempt_id, snapshot_uri, snapshot_digest, version FROM task_projection_snapshots "
                        + "WHERE task_id = ?", (row, index) -> new ProjectionReference(
                        row.getString(1), row.getString(2), row.getString(3), row.getLong(4)), taskId);
        return references.stream().findFirst().map(reference -> restore(taskId, reference));
    }

    @Override
    public List<TaskState> list() {
        return jdbc.query("SELECT p.task_id, p.attempt_id, p.snapshot_uri, p.snapshot_digest, p.version "
                        + "FROM task_projection_snapshots p JOIN tasks t ON t.task_id = p.task_id "
                        + "ORDER BY t.created_at, p.task_id",
                (row, index) -> restore(row.getString(1), new ProjectionReference(
                        row.getString(2), row.getString(3), row.getString(4), row.getLong(5))));
    }

    private TaskState restore(String taskId, ProjectionReference reference) {
        EvidenceRepository.RawEvidence raw = evidence.read(new EvidenceRepository.ReadRequest(
                taskId, reference.attemptId(), reference.uri(), "workflow", "projection-recovery"));
        if (!reference.uri().equals(raw.uri()) || !reference.digest().equals(raw.digest())
                || !reference.digest().equals(sha256(raw.content())) || !"COMPLETE".equals(raw.status())) {
            throw new SecurityException("Task projection snapshot failed Evidence verification");
        }
        ProjectionSnapshot snapshot;
        try {
            snapshot = mapper.readValue(raw.content(), ProjectionSnapshot.class);
        } catch (Exception failure) {
            throw new IllegalStateException("Task projection snapshot cannot be decoded", failure);
        }
        if (!taskId.equals(snapshot.view().id()) || !reference.attemptId().equals(snapshot.attemptId())) {
            throw new SecurityException("Task projection snapshot identity diverged");
        }
        return restoreState(snapshot, reference.version());
    }

    private static TaskState restoreState(ProjectionSnapshot snapshot, long version) {
        TaskView view = snapshot.view();
        TaskState state = new TaskState(view.id(), view.ticketNumber(), new TaskRequest(
                view.repositoryUrl(), view.baseBranch(), view.requirement(),
                view.llmMode() == null ? LlmMode.CLOUD : view.llmMode()), view.createdAt());
        state.status = view.status();
        state.workspace = view.workspace();
        state.sourceCommit = view.sourceCommit();
        state.model = view.model();
        state.promptFingerprints.putAll(view.promptFingerprints());
        state.plan = view.plan(); state.patch = view.patch(); state.testSummary = view.testSummary();
        state.qualitySummary = view.qualitySummary(); state.securitySummary = view.securitySummary();
        state.assuranceResults.putAll(view.assuranceResults());
        state.review = view.review(); state.pendingEffect = view.pendingEffect();
        state.pullRequestUrl = view.pullRequestUrl(); state.error = view.error();
        state.steps.addAll(view.steps()); state.updatedAt = view.updatedAt();
        state.executionMode = view.executionMode(); state.workflowRunId = view.workflowRunId();
        state.dagVersion = view.dagVersion();
        if (view.globalBudget() != null) {
            state.globalMaxTokens = view.globalBudget().maxTokens();
            state.globalMaxCostMicros = view.globalBudget().maxCostMicros();
            state.globalMaxTurns = view.globalBudget().maxTurns();
            state.llmTokens = view.globalBudget().usedTokens();
            state.llmCostMicros = view.globalBudget().usedCostMicros();
            state.agentTurns = view.globalBudget().usedTurns();
        }
        Object repairs = view.evaluationMetrics().get("repairs");
        state.patchRepairs = repairs instanceof Number number ? number.intValue() : 0;
        state.testsPassed = Boolean.TRUE.equals(view.evaluationMetrics().get("tests_passed"));
        state.reviewAccepted = Boolean.TRUE.equals(view.evaluationMetrics().get("review_accepted"));
        state.humanApproved = Boolean.TRUE.equals(view.evaluationMetrics().get("human_accepted"));
        view.delegations().forEach(item -> {
            state.recordDelegation(item.delegationId(), item.parentDelegationId(), item.role(), item.dependsOn(),
                    item.status(), item.stopReason(), item.durationMillis(), item.turns(), item.tokens(),
                    item.costMicros(), item.toolsUsed());
            if (item.codeImpact() != null) state.recordDelegationCodeImpact(item.delegationId(),
                    item.codeImpact().scopes(), item.codeImpact().touchedFiles(), item.codeImpact().collisions());
        });
        view.artifacts().forEach(item -> state.recordArtifact(item.artifactId(), item.type(), item.status(),
                item.classification(), item.uri(), item.digest(), item.sizeBytes(), item.uri() != null));
        view.contradictions().forEach(item -> state.contradictions.put(item.contradictionId(), item));
        view.decisions().forEach(item -> state.decisions.put(item.decisionId(), item));
        view.humanActions().forEach(item -> state.humanActions.put(item.requestId(), item));
        state.updatedAt = view.updatedAt();
        state.restoreProjectionMetadata(snapshot.attemptId(), version, snapshot.approvalExpiresAt());
        return state;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record ProjectionSnapshot(TaskView view, String attemptId, Instant approvalExpiresAt) {}
    private record ProjectionReference(String attemptId, String uri, String digest, long version) {}

    public static final class OptimisticProjectionLockException extends IllegalStateException {
        OptimisticProjectionLockException(String taskId, long version, Throwable cause) {
            super("Optimistic projection conflict for task " + taskId + " at version " + version, cause);
        }
    }
}
