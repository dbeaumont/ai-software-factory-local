package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskMemorySchemaTest {
    private static final Path DATABASE = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources/multiagents/database").normalize();

    @Test
    void coreMigrationModelsTaskWorkflowDelegationAgentDecisionAndApproval() throws Exception {
        String sql = Files.readString(DATABASE.resolve("V001__task_memory_core.sql"));

        for (String table : List.of("tasks", "workflow_runs", "delegations", "agent_runs",
                "decisions", "approvals")) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).contains("REFERENCES tasks(task_id)")
                .contains("REFERENCES workflow_runs(workflow_run_id)")
                .contains("REFERENCES delegations(delegation_id)")
                .contains("UNIQUE (task_id, attempt_id)")
                .contains("UNIQUE (task_id, attempt_id, manifest_id, approver)");
    }

    @Test
    void evidenceMigrationModelsReferencesContradictionsBudgetsAndToolsWithoutRawContent() throws Exception {
        String sql = Files.readString(DATABASE.resolve("V002__task_memory_evidence_and_usage.sql"));

        for (String table : List.of("artifacts", "evidence_refs", "contradictions", "budget_usage",
                "tool_invocations")) {
            assertThat(sql).contains("CREATE TABLE " + table + " (");
        }
        assertThat(sql).contains("evidence_uri")
                .contains("REFERENCES artifacts(artifact_id)")
                .contains("REFERENCES evidence_refs(evidence_ref_id)")
                .contains("UNIQUE (delegation_id, sequence_number)")
                .contains("UNIQUE (task_id, attempt_id, operation_id)")
                .doesNotContain("content_base64", "raw_content", "artifact_bytes");
    }
}
