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

    @Test
    void everyRecordCarriesAttemptAndCommitAndCompositeForeignKeysEnforceLineage() throws Exception {
        String model = Files.readString(DATABASE.resolve("V001__task_memory_core.sql"))
                + Files.readString(DATABASE.resolve("V002__task_memory_evidence_and_usage.sql"));
        for (String table : List.of("tasks", "workflow_runs", "delegations", "agent_runs", "decisions",
                "approvals", "artifacts", "evidence_refs", "contradictions", "budget_usage",
                "tool_invocations")) {
            String definition = model.substring(model.indexOf("CREATE TABLE " + table + " ("));
            definition = definition.substring(0, definition.indexOf("\n);"));
            assertThat(definition).as(table).contains("attempt_id", "source_commit");
        }
        String lineage = Files.readString(DATABASE.resolve("V003__enforce_attempt_and_commit_lineage.sql"));
        assertThat(lineage).contains("workflow_runs_attempt_uq")
                .contains("delegations_workflow_lineage_fk")
                .contains("agent_runs_delegation_lineage_fk")
                .contains("evidence_refs_artifact_lineage_fk")
                .contains("tool_invocations_agent_lineage_fk");
        assertThat(lineage.split("FOREIGN KEY \\(.*task_id, attempt_id, source_commit\\)", -1).length - 1)
                .isGreaterThanOrEqualTo(10);
    }

    @Test
    void trustMigrationSeparatesEvidenceInputAgentConclusionsAndPolicyDecisions() throws Exception {
        String sql = Files.readString(DATABASE.resolve("V004__separate_information_trust_domains.sql"));

        assertThat(sql).contains("'VERIFIED_EVIDENCE'")
                .contains("'UNTRUSTED_INPUT'")
                .contains("'AGENT_CONCLUSION'")
                .contains("'POLICY_DECISION'")
                .contains("artifacts_verified_status_ck")
                .contains("evidence_refs_verified_status_ck")
                .contains("decisions_policy_actor_ck");
        assertThat(sql.split("ADD COLUMN information_kind information_kind NOT NULL", -1).length - 1)
                .isEqualTo(3);
    }
}
