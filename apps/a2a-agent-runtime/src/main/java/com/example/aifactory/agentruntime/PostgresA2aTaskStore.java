package com.example.aifactory.agentruntime;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** PostgreSQL task projection with unique idempotency identity and optimistic version transitions. */
public final class PostgresA2aTaskStore implements A2aTaskStore {
    private static final RowMapper<StoredTask> TASK_MAPPER = PostgresA2aTaskStore::task;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;

    public PostgresA2aTaskStore(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @Override
    public CreateResult createOrGet(StoredTask candidate, HistoryRecord accepted) {
        try {
            return transactions.execute(status -> {
                jdbc.update("""
                        INSERT INTO a2a_agent_task
                          (task_id, context_id, message_id, message_digest, agent_role, skill_id,
                           caller_subject, tenant_id, delegation_id, submitted_at, task_state, version, envelope_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, candidate.taskId(), candidate.contextId(), candidate.messageId(),
                        candidate.messageDigest(), candidate.role(), candidate.skill(), candidate.callerSubject(),
                        candidate.tenantId(), candidate.delegationId(), Timestamp.from(candidate.submittedAt()),
                        candidate.state().name(), candidate.version(), candidate.envelopeJson());
                insertHistory(candidate.taskId(), new HistoryRecord(
                        accepted.messageId(), accepted.event(), accepted.occurredAt(), candidate.version()));
                return new CreateResult(candidate, true);
            });
        } catch (DuplicateKeyException duplicate) {
            StoredTask existing = jdbc.query("SELECT * FROM a2a_agent_task WHERE message_id = ?",
                    TASK_MAPPER, candidate.messageId()).stream().findFirst().orElseThrow(() -> duplicate);
            return new CreateResult(existing, false);
        }
    }

    @Override
    public Optional<StoredTask> find(String taskId) {
        return jdbc.query("SELECT * FROM a2a_agent_task WHERE task_id = ?", TASK_MAPPER, taskId)
                .stream().findFirst();
    }

    @Override
    public Optional<StoredTask> findByMessageId(String messageId) {
        return jdbc.query("SELECT * FROM a2a_agent_task WHERE message_id = ?", TASK_MAPPER, messageId)
                .stream().findFirst();
    }

    @Override
    public List<StoredTask> list(String tenantId, String callerSubject, String contextId,
                                 A2aSendMessageService.TaskState state, int offset, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT * FROM a2a_agent_task
                WHERE tenant_id = ? AND caller_subject = ?
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(List.of(tenantId, callerSubject));
        if (contextId != null) { sql.append(" AND context_id = ?"); args.add(contextId); }
        if (state != null) { sql.append(" AND task_state = ?"); args.add(state.name()); }
        sql.append(" ORDER BY submitted_at, task_id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        args.add(offset);
        args.add(limit);
        return jdbc.query(sql.toString(), TASK_MAPPER, args.toArray());
    }

    @Override
    public int count(String tenantId, String callerSubject, String contextId,
                     A2aSendMessageService.TaskState state) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM a2a_agent_task
                WHERE tenant_id = ? AND caller_subject = ?
                """);
        java.util.ArrayList<Object> args = new java.util.ArrayList<>(List.of(tenantId, callerSubject));
        if (contextId != null) { sql.append(" AND context_id = ?"); args.add(contextId); }
        if (state != null) { sql.append(" AND task_state = ?"); args.add(state.name()); }
        Integer count = jdbc.queryForObject(sql.toString(), Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    @Override
    public List<HistoryRecord> history(String taskId, int limit) {
        List<HistoryRecord> descending = jdbc.query("""
                        SELECT message_id, event_type, occurred_at, task_version FROM a2a_agent_task_history
                        WHERE task_id = ? ORDER BY sequence_id DESC FETCH FIRST ? ROWS ONLY
                        """, (rs, row) -> new HistoryRecord(rs.getString("message_id"),
                        rs.getString("event_type"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getLong("task_version")), taskId, limit);
        java.util.Collections.reverse(descending);
        return List.copyOf(descending);
    }

    @Override
    public List<Map<String, Object>> artifacts(String taskId, String tenantId, String callerSubject) {
        return jdbc.query("""
                        SELECT artifact_json FROM a2a_agent_task_artifact
                        WHERE task_id = ? AND tenant_id = ? AND acl_subject = ? ORDER BY artifact_id
                        """, (rs, row) -> readArtifact(rs.getString("artifact_json")),
                taskId, tenantId, callerSubject);
    }

    @Override
    public Optional<StoredTask> transition(String taskId, long expectedVersion,
                                           A2aSendMessageService.TaskState next, HistoryRecord event) {
        return transactions.execute(status -> {
            int updated = jdbc.update("""
                    UPDATE a2a_agent_task SET task_state = ?, version = version + 1
                    WHERE task_id = ? AND version = ?
                    """, next.name(), taskId, expectedVersion);
            if (updated == 0) return Optional.empty();
            insertHistory(taskId, new HistoryRecord(
                    event.messageId(), event.event(), event.occurredAt(), expectedVersion + 1));
            return find(taskId);
        });
    }

    @Override
    public void recordWorkflowExecution(String taskId, String workflowId, String runId) {
        int updated = jdbc.update("""
                UPDATE a2a_agent_task SET workflow_id = ?, workflow_run_id = ?
                WHERE task_id = ? AND (workflow_id IS NULL OR workflow_id = ?)
                """, workflowId, runId, taskId, workflowId);
        if (updated != 1) throw new IllegalStateException("A2A task is absent or bound to another workflow");
    }

    @Override
    public List<StoredTask> nonTerminal(String role, int limit) {
        return jdbc.query("""
                SELECT * FROM a2a_agent_task
                WHERE agent_role = ? AND task_state NOT IN ('COMPLETED', 'REJECTED', 'FAILED', 'CANCELED')
                ORDER BY submitted_at, task_id FETCH FIRST ? ROWS ONLY
                """, TASK_MAPPER, role, limit);
    }

    @Override
    public void enqueueNotification(PendingNotification notification) {
        try {
            jdbc.update("""
                    INSERT INTO a2a_agent_notification_outbox
                      (notification_id, task_id, context_id, agent_role, task_sequence, task_state, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, notification.notificationId(), notification.taskId(), notification.contextId(),
                    notification.role(), notification.sequence(), notification.state().name(),
                    Timestamp.from(notification.occurredAt()));
        } catch (DuplicateKeyException alreadyQueued) {
            // The deterministic notification ID makes an activity retry a no-op.
        }
    }

    @Override
    public List<PendingNotification> pendingNotifications(String role, int limit) {
        return jdbc.query("""
                SELECT notification_id, task_id, context_id, agent_role, task_sequence, task_state, occurred_at
                FROM a2a_agent_notification_outbox
                WHERE agent_role = ? AND acknowledged_at IS NULL
                ORDER BY occurred_at, notification_id FETCH FIRST ? ROWS ONLY
                """, (rs, row) -> new PendingNotification(rs.getString("notification_id"), rs.getString("task_id"),
                rs.getString("context_id"), rs.getString("agent_role"), rs.getLong("task_sequence"),
                A2aSendMessageService.TaskState.valueOf(rs.getString("task_state")),
                rs.getTimestamp("occurred_at").toInstant()), role, limit);
    }

    @Override
    public void acknowledgeNotification(String notificationId, java.time.Instant acknowledgedAt) {
        jdbc.update("""
                UPDATE a2a_agent_notification_outbox SET acknowledged_at = ?
                WHERE notification_id = ? AND acknowledged_at IS NULL
                """, Timestamp.from(acknowledgedAt), notificationId);
    }

    @Override
    public void putArtifact(ArtifactRecord artifact) {
        try {
            jdbc.update("""
                    INSERT INTO a2a_agent_task_artifact
                      (artifact_id, task_id, tenant_id, acl_subject, artifact_digest, artifact_json, version)
                    VALUES (?, ?, ?, ?, ?, ?, 0)
                    """, artifact.artifactId(), artifact.taskId(), artifact.tenantId(), artifact.aclSubject(),
                    artifact.digest(), writeArtifact(artifact.document()));
        } catch (DuplicateKeyException replay) {
            Map<String, Object> existing = jdbc.queryForObject("""
                    SELECT artifact_json FROM a2a_agent_task_artifact WHERE artifact_id = ?
                    """, (rs, row) -> readArtifact(rs.getString("artifact_json")), artifact.artifactId());
            if (!artifact.document().equals(existing)) throw new IllegalStateException("Immutable A2A artifact conflict");
        }
    }

    @Override
    public void checkHealth() {
        Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
        if (!Integer.valueOf(1).equals(value)) throw new IllegalStateException("A2A task store health check failed");
    }

    @Override
    public int activeCount(String role, String tenantId) {
        String sql = """
                SELECT COUNT(*) FROM a2a_agent_task
                WHERE agent_role = ? AND task_state NOT IN ('COMPLETED', 'REJECTED', 'FAILED', 'CANCELED')
                """ + (tenantId == null ? "" : " AND tenant_id = ?");
        Integer count = tenantId == null ? jdbc.queryForObject(sql, Integer.class, role)
                : jdbc.queryForObject(sql, Integer.class, role, tenantId);
        return count == null ? 0 : count;
    }

    private void insertHistory(String taskId, HistoryRecord history) {
        jdbc.update("""
                INSERT INTO a2a_agent_task_history (task_id, message_id, event_type, occurred_at, task_version)
                VALUES (?, ?, ?, ?, ?)
                """, taskId, history.messageId(), history.event(), Timestamp.from(history.occurredAt()),
                history.taskVersion());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readArtifact(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored A2A artifact is invalid", exception);
        }
    }

    private String writeArtifact(Map<String, Object> artifact) {
        try {
            return mapper.writeValueAsString(artifact);
        } catch (Exception exception) {
            throw new IllegalArgumentException("A2A artifact is not serializable", exception);
        }
    }

    private static StoredTask task(ResultSet rs, int row) throws SQLException {
        return new StoredTask(rs.getString("task_id"), rs.getString("context_id"),
                rs.getString("message_id"), rs.getString("message_digest"), rs.getString("agent_role"),
                rs.getString("skill_id"), rs.getString("caller_subject"), rs.getString("tenant_id"),
                rs.getString("delegation_id"), rs.getTimestamp("submitted_at").toInstant(),
                A2aSendMessageService.TaskState.valueOf(rs.getString("task_state")), rs.getLong("version"),
                rs.getString("envelope_json"), rs.getString("workflow_id"), rs.getString("workflow_run_id"));
    }
}
