package com.example.aifactory.workflow.projection;

import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public final class PostgresA2aTaskAssociationStore implements A2aTaskAssociationStore {
    private final JdbcTemplate jdbc;

    public PostgresA2aTaskAssociationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void record(A2aExecutionContext execution, String a2aTaskId, String a2aContextId) {
        requireServerId("Task.id", a2aTaskId);
        requireServerId("contextId", a2aContextId);
        int rows = jdbc.update("INSERT INTO a2a_task_associations(delegation_id, task_id, attempt_id, "
                        + "source_commit, agent_role, a2a_task_id, a2a_context_id) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (delegation_id) DO UPDATE SET updated_at = now() "
                        + "WHERE a2a_task_associations.task_id = excluded.task_id "
                        + "AND a2a_task_associations.attempt_id = excluded.attempt_id "
                        + "AND a2a_task_associations.source_commit = excluded.source_commit "
                        + "AND a2a_task_associations.agent_role = excluded.agent_role "
                        + "AND a2a_task_associations.a2a_task_id = excluded.a2a_task_id "
                        + "AND a2a_task_associations.a2a_context_id = excluded.a2a_context_id",
                execution.delegationId(), execution.taskId(), execution.attemptId(), execution.sourceCommit(),
                execution.agentRole(), a2aTaskId, a2aContextId);
        if (rows != 1) throw new SecurityException("Divergent A2A task association for business delegation");
    }

    @Override
    public Optional<Association> findByDelegation(String delegationId) {
        return jdbc.query("SELECT delegation_id, task_id, attempt_id, source_commit, agent_role, a2a_task_id, "
                        + "a2a_context_id FROM a2a_task_associations WHERE delegation_id = ?",
                (row, index) -> new Association(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), row.getString(6), row.getString(7)), delegationId)
                .stream().findFirst();
    }

    private static void requireServerId(String name, String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException(name + " returned by the A2A server is invalid");
        }
    }
}
