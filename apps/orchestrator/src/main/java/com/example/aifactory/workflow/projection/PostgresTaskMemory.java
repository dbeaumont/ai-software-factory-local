package com.example.aifactory.workflow.projection;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.service.ScmDeliveryGateway;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.TaskMemory;
import com.example.aifactory.workflow.temporal.TemporalIds;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
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
    private final Duration staleAfter;

    @Autowired
    public PostgresTaskMemory(JdbcTemplate jdbc, TransactionTemplate transactions,
                              EvidenceRepository evidence, ObjectMapper mapper,
                              @Value("${ai-factory.temporal.projection-stale-after:PT30S}") Duration staleAfter) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.evidence = evidence;
        this.mapper = mapper;
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("Projection stale threshold must be positive");
        }
        this.staleAfter = staleAfter;
    }

    PostgresTaskMemory(JdbcTemplate jdbc, TransactionTemplate transactions,
                       EvidenceRepository evidence, ObjectMapper mapper) {
        this(jdbc, transactions, evidence, mapper, Duration.ofSeconds(30));
    }

    @Override
    public void save(TaskState state) {
        EvidenceRepository.StoredEvidence snapshot = storeSnapshot(state);
        transactions.executeWithoutResult(ignored -> persistMetadata(state, snapshot));
    }

    @Override
    public void admit(TaskState state) {
        EvidenceRepository.StoredEvidence snapshot = storeSnapshot(state);
        transactions.executeWithoutResult(ignored -> {
            persistMetadata(state, snapshot);
            jdbc.update("INSERT INTO task_admission_outbox(task_id, attempt_id, workflow_id, status, "
                            + "next_attempt_at, created_at, updated_at, version) "
                            + "VALUES (?, ?, ?, 'PENDING', ?, ?, ?, 0)",
                    state.id, state.workflowAttemptId, workflowId(state), Instant.now(), state.createdAt,
                    state.updatedAt);
        });
    }

    @Override
    public void workflowStarted(TaskState state) {
        EvidenceRepository.StoredEvidence snapshot = storeSnapshot(state);
        transactions.executeWithoutResult(ignored -> {
            persistMetadata(state, snapshot);
            int updated = jdbc.update("UPDATE task_admission_outbox SET status = 'STARTED', updated_at = ?, "
                            + "version = version + 1 WHERE task_id = ? AND attempt_id = ? AND status = 'PENDING'",
                    Instant.now(), state.id, state.workflowAttemptId);
            if (updated != 1) throw new IllegalStateException("Task admission intent is missing or already closed");
        });
    }

    @Override
    public List<TaskState> pendingAdmissions(int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Admission batch limit is invalid");
        return jdbc.query("SELECT o.task_id, p.attempt_id, p.snapshot_uri, p.snapshot_digest, p.version "
                        + "FROM task_admission_outbox o JOIN task_projection_snapshots p ON p.task_id = o.task_id "
                        + "WHERE o.status = 'PENDING' AND o.next_attempt_at <= ? ORDER BY o.created_at LIMIT ?",
                (row, index) -> restore(row.getString(1), new ProjectionReference(
                        row.getString(2), row.getString(3), row.getString(4), row.getLong(5))),
                Instant.now(), limit);
    }

    @Override
    public void admissionFailed(TaskState state, RuntimeException failure) {
        String errorCode = failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
        transactions.executeWithoutResult(ignored -> {
            List<Integer> retries = jdbc.query("SELECT retry_count FROM task_admission_outbox "
                            + "WHERE task_id = ? AND attempt_id = ? AND status = 'PENDING' FOR UPDATE",
                    (row, index) -> row.getInt(1), state.id, state.workflowAttemptId);
            if (retries.isEmpty()) return;
            int retry = retries.getFirst() + 1;
            long delaySeconds = Math.min(300, 1L << Math.min(retry, 8));
            jdbc.update("UPDATE task_admission_outbox SET retry_count = ?, last_error_code = ?, "
                            + "next_attempt_at = ?, updated_at = ?, version = version + 1 "
                            + "WHERE task_id = ? AND attempt_id = ? AND status = 'PENDING'",
                    retry, errorCode.substring(0, Math.min(errorCode.length(), 128)),
                    Instant.now().plusSeconds(delaySeconds), Instant.now(), state.id, state.workflowAttemptId);
        });
    }

    @Override
    public boolean project(String eventId, TaskState state) {
        requireEventId(eventId);
        EvidenceRepository.StoredEvidence snapshot = storeSnapshot(state);
        Boolean applied = transactions.execute(ignored -> {
            if (wasProjected(state.id, state.workflowAttemptId, eventId)) return false;
            persistMetadata(state, snapshot);
            jdbc.update("INSERT INTO task_projection_events(task_id, attempt_id, event_id, snapshot_digest, "
                            + "projected_at) VALUES (?, ?, ?, ?, ?)",
                    state.id, state.workflowAttemptId, eventId, snapshot.digest(), Instant.now());
            Long position = jdbc.queryForObject("SELECT projection_position FROM task_projection_events "
                            + "WHERE task_id = ? AND attempt_id = ? AND event_id = ?",
                    Long.class, state.id, state.workflowAttemptId, eventId);
            jdbc.update("UPDATE task_projection_snapshots SET last_event_position = ?, last_event_id = ? "
                            + "WHERE task_id = ?", position, eventId, state.id);
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    @Override
    public boolean wasProjected(String taskId, String attemptId, String eventId) {
        requireEventId(eventId);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_projection_events "
                        + "WHERE task_id = ? AND attempt_id = ? AND event_id = ?",
                Integer.class, taskId, attemptId, eventId);
        return count != null && count > 0;
    }

    @Override
    public Optional<ProjectionStatus> projectionStatus(String taskId) {
        List<ProjectionStatus> status = jdbc.query("SELECT task_id, attempt_id, last_event_position, "
                        + "last_event_id, projected_at FROM task_projection_snapshots WHERE task_id = ?",
                (row, index) -> {
                    Instant projectedAt = row.getObject(5, java.time.OffsetDateTime.class).toInstant();
                    long age = Math.max(0, Duration.between(projectedAt, Instant.now()).toMillis());
                    return new ProjectionStatus(row.getString(1), row.getString(2), row.getLong(3),
                            row.getString(4), projectedAt, age, age > staleAfter.toMillis());
                }, taskId);
        return status.stream().findFirst();
    }

    private EvidenceRepository.StoredEvidence storeSnapshot(TaskState state) {
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
        return evidence.store(new EvidenceRepository.StoreRequest(
                state.id, state.workflowAttemptId, "metadata", "application/json", bytes, digest, "workflow"));
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

    private static String workflowId(TaskState state) {
        return TemporalIds.workflow(state.id, state.workflowAttemptId);
    }

    private static void requireEventId(String eventId) {
        if (eventId == null || !eventId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}")) {
            throw new IllegalArgumentException("Projection event identity is invalid");
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
