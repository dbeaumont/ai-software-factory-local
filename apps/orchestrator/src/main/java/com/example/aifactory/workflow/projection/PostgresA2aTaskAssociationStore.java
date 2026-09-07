package com.example.aifactory.workflow.projection;

import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class PostgresA2aTaskAssociationStore implements A2aTaskAssociationStore {
    private final JdbcTemplate jdbc;

    public PostgresA2aTaskAssociationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void prepareDelegation(A2aExecutionContext execution, DispatchIntent intent) {
        int rows = jdbc.update("INSERT INTO delegations(delegation_id, parent_delegation_id, workflow_run_id, "
                        + "task_id, attempt_id, source_commit, role, objective_digest, budget_tokens, "
                        + "budget_cost_micros, budget_turns, status, created_at, completed_at, updated_at, version) "
                        + "SELECT ?, CAST(? AS varchar(128)), workflow.workflow_run_id, ?, ?, ?, ?, ?, ?, ?, ?, "
                        + "'DISPATCHING', now(), NULL, now(), 0 FROM workflow_runs workflow "
                        + "WHERE workflow.task_id = ? AND workflow.attempt_id = ? AND workflow.source_commit = ? "
                        + "AND (CAST(? AS varchar(128)) IS NULL OR EXISTS (SELECT 1 FROM delegations parent "
                        + "WHERE parent.delegation_id = ? AND parent.task_id = ? AND parent.attempt_id = ? "
                        + "AND parent.source_commit = ?)) "
                        + "ON CONFLICT (delegation_id) DO UPDATE SET updated_at = now() "
                        + "WHERE delegations.parent_delegation_id IS NOT DISTINCT FROM excluded.parent_delegation_id "
                        + "AND delegations.workflow_run_id = excluded.workflow_run_id "
                        + "AND delegations.task_id = excluded.task_id "
                        + "AND delegations.attempt_id = excluded.attempt_id "
                        + "AND delegations.source_commit = excluded.source_commit "
                        + "AND delegations.role = excluded.role "
                        + "AND delegations.objective_digest = excluded.objective_digest "
                        + "AND delegations.budget_tokens = excluded.budget_tokens "
                        + "AND delegations.budget_cost_micros = excluded.budget_cost_micros "
                        + "AND delegations.budget_turns = excluded.budget_turns",
                execution.delegationId(), execution.parentDelegationId(), execution.taskId(), execution.attemptId(),
                execution.sourceCommit(), execution.agentRole(), intent.objectiveDigest(), intent.budgetTokens(),
                intent.budgetCostMicros(), intent.budgetTurns(), execution.taskId(), execution.attemptId(),
                execution.sourceCommit(), execution.parentDelegationId(), execution.parentDelegationId(),
                execution.taskId(), execution.attemptId(), execution.sourceCommit());
        if (rows != 1) {
            throw new SecurityException("Missing or divergent durable A2A delegation lineage");
        }
    }

    @Override
    public void record(A2aExecutionContext execution, String messageId, String agentCardDigest,
                       String a2aTaskId, String a2aContextId) {
        requireServerId("Task.id", a2aTaskId);
        requireServerId("contextId", a2aContextId);
        requireServerId("messageId", messageId);
        if (agentCardDigest == null || !agentCardDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Agent Card digest is invalid");
        }
        int rows = jdbc.update("INSERT INTO a2a_task_associations(delegation_id, task_id, attempt_id, "
                        + "workflow_id, workflow_run_id, source_commit, message_id, agent_role, agent_card_digest, "
                        + "a2a_task_id, a2a_context_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (delegation_id) DO UPDATE SET updated_at = now() "
                        + "WHERE a2a_task_associations.task_id = excluded.task_id "
                        + "AND a2a_task_associations.attempt_id = excluded.attempt_id "
                        + "AND a2a_task_associations.workflow_id = excluded.workflow_id "
                        + "AND a2a_task_associations.workflow_run_id = excluded.workflow_run_id "
                        + "AND a2a_task_associations.source_commit = excluded.source_commit "
                        + "AND a2a_task_associations.message_id = excluded.message_id "
                        + "AND a2a_task_associations.agent_role = excluded.agent_role "
                        + "AND a2a_task_associations.agent_card_digest = excluded.agent_card_digest "
                        + "AND a2a_task_associations.a2a_task_id = excluded.a2a_task_id "
                        + "AND a2a_task_associations.a2a_context_id = excluded.a2a_context_id",
                execution.delegationId(), execution.taskId(), execution.attemptId(), execution.workflowId(),
                execution.workflowRunId(), execution.sourceCommit(), messageId, execution.agentRole(),
                agentCardDigest, a2aTaskId, a2aContextId);
        if (rows != 1) throw new SecurityException("Divergent A2A task association for business delegation");
    }

    @Override
    public Optional<Association> findByDelegation(String delegationId) {
        return find("delegation_id", delegationId, null);
    }

    @Override
    public Optional<Association> findByMessageId(String agentRole, String messageId) {
        return find("message_id", messageId, agentRole);
    }

    @Override
    public Optional<Association> findByA2aTaskId(String agentRole, String a2aTaskId) {
        return find("a2a_task_id", a2aTaskId, agentRole);
    }

    private Optional<Association> find(String column, String value, String agentRole) {
        String sql = "SELECT delegation_id, task_id, attempt_id, workflow_id, workflow_run_id, source_commit, "
                        + "message_id, agent_role, agent_card_digest, a2a_task_id, a2a_context_id "
                        + "FROM a2a_task_associations WHERE " + column + " = ?"
                        + (agentRole == null ? "" : " AND agent_role = ?");
        Object[] arguments = agentRole == null ? new Object[]{value} : new Object[]{value, agentRole};
        return jdbc.query(sql,
                (row, index) -> new Association(row.getString(1), row.getString(2), row.getString(3),
                        row.getString(4), row.getString(5), row.getString(6), row.getString(7), row.getString(8),
                        row.getString(9), row.getString(10), row.getString(11)), arguments)
                .stream().findFirst();
    }

    private static void requireServerId(String name, String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException(name + " returned by the A2A server is invalid");
        }
    }
}
