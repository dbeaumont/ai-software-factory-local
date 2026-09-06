package com.example.aifactory.workflow.projection;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aNotificationInbox;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class PostgresA2aNotificationInbox implements A2aNotificationInbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public PostgresA2aNotificationInbox(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public Admission admit(A2aTaskAssociationStore.Association association,
                           A2aContracts.Notification notification, String payloadDigest, String payloadJson) {
        return transactions.execute(status -> {
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?)) IS NULL", Boolean.class,
                    notification.agentRole() + ':' + notification.taskId());
            List<Row> existing = jdbc.query("""
                    SELECT context_id, task_state, payload_digest, signal_status
                    FROM a2a_notification_inbox
                    WHERE agent_role = ? AND a2a_task_id = ? AND transition_sequence = ?
                    """, (row, index) -> new Row(row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
                    notification.agentRole(), notification.taskId(), notification.sequence());
            if (!existing.isEmpty()) {
                Row value = existing.getFirst();
                if (!value.contextId().equals(notification.contextId())
                        || !value.state().equals(notification.state().name())
                        || !value.digest().equals(payloadDigest)) {
                    throw new SecurityException("Divergent A2A notification replay");
                }
                return new Admission(!"SIGNALLED".equals(value.status()));
            }
            Long maximum = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(transition_sequence), -1) FROM a2a_notification_inbox
                    WHERE agent_role = ? AND a2a_task_id = ?
                    """, Long.class, notification.agentRole(), notification.taskId());
            if (maximum != null && notification.sequence() <= maximum) {
                throw new SecurityException("Out-of-order A2A notification");
            }
            jdbc.update("""
                    INSERT INTO a2a_notification_inbox
                      (agent_role, a2a_task_id, context_id, transition_sequence, task_state, payload_digest,
                       payload_json, workflow_id, workflow_run_id, signal_status, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                    """, notification.agentRole(), notification.taskId(), notification.contextId(),
                    notification.sequence(), notification.state().name(), payloadDigest, payloadJson,
                    association.workflowId(), association.workflowRunId(), Timestamp.from(notification.occurredAt()));
            return new Admission(true);
        });
    }

    @Override
    public void markSignalled(String agentRole, String taskId, long sequence, Instant signalledAt) {
        jdbc.update("""
                UPDATE a2a_notification_inbox SET signal_status = 'SIGNALLED', signalled_at = ?
                WHERE agent_role = ? AND a2a_task_id = ? AND transition_sequence = ?
                """, Timestamp.from(signalledAt), agentRole, taskId, sequence);
    }

    private record Row(String contextId, String state, String digest, String status) {}
}
