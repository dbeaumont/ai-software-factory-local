package com.example.aifactory.workflow.projection;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresUiProjectionStoreTest {
    private JdbcTemplate jdbc;
    private PostgresUiProjectionStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ui-projection-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tasks(task_id varchar PRIMARY KEY, repository_id varchar, "
                + "current_attempt_id varchar, source_commit varchar, requirement_digest varchar, status varchar, "
                + "created_at timestamp with time zone, updated_at timestamp with time zone, version bigint)");
        jdbc.execute("CREATE TABLE workflow_runs(workflow_run_id varchar PRIMARY KEY, workflow_id varchar, "
                + "temporal_run_id uuid, task_id varchar, attempt_id varchar, source_commit varchar, status varchar, "
                + "started_at timestamp with time zone, completed_at timestamp with time zone, "
                + "updated_at timestamp with time zone, version bigint)");
        jdbc.execute("CREATE TABLE delegations(delegation_id varchar PRIMARY KEY, parent_delegation_id varchar, "
                + "workflow_run_id varchar, task_id varchar, attempt_id varchar, source_commit varchar, role varchar, "
                + "objective_digest varchar, budget_tokens bigint, budget_cost_micros bigint, budget_turns integer, "
                + "status varchar, created_at timestamp with time zone, completed_at timestamp with time zone, "
                + "updated_at timestamp with time zone, version bigint)");
        jdbc.execute("CREATE TABLE artifacts(artifact_id varchar PRIMARY KEY, task_id varchar, attempt_id varchar, "
                + "source_commit varchar, artifact_type varchar, evidence_uri varchar, digest varchar, status varchar, "
                + "classification varchar, trust_domain varchar, media_type varchar, size_bytes bigint, "
                + "created_at timestamp with time zone, retain_until timestamp with time zone, version bigint, "
                + "information_kind varchar)");
        jdbc.execute("CREATE TABLE evidence_refs(evidence_ref_id varchar PRIMARY KEY, artifact_id varchar, "
                + "workflow_run_id varchar, task_id varchar, attempt_id varchar, source_commit varchar, "
                + "purpose varchar, verification varchar, verified_at timestamp with time zone, "
                + "created_at timestamp with time zone, version bigint, information_kind varchar)");
        for (String table : List.of("tool_invocations", "budget_usage", "agent_runs", "contradictions")) {
            jdbc.execute("CREATE TABLE " + table + "(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                    + "task_id varchar)");
        }
        store = new PostgresUiProjectionStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void atomicallyReplacesTaskWorkflowDelegationAndVerifiedEvidenceMetadata() {
        UiProjectionSnapshot snapshot = snapshot(List.of(evidence("artifact-1")));

        store.replaceAtomically(snapshot);

        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = 'task-1'", String.class))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_runs", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delegations", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT verification, information_kind FROM evidence_refs"))
                .containsEntry("VERIFICATION", "VERIFIED")
                .containsEntry("INFORMATION_KIND", "VERIFIED_EVIDENCE");
    }

    @Test
    void rollsBackTheWholeReplacementWhenAnyMetadataInsertFails() {
        store.replaceAtomically(snapshot(List.of(evidence("artifact-old"))));
        UiProjectionSnapshot.Evidence duplicate = evidence("artifact-duplicate");

        assertThatThrownBy(() -> store.replaceAtomically(snapshot(List.of(duplicate, duplicate))))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForObject("SELECT artifact_id FROM artifacts", String.class))
                .isEqualTo("artifact-old");
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE task_id = 'task-1'", String.class))
                .isEqualTo("APPROVED");
    }

    private static UiProjectionSnapshot snapshot(List<UiProjectionSnapshot.Evidence> evidence) {
        Instant started = Instant.parse("2026-09-06T10:00:00Z");
        String runId = "3d45f820-11d4-4e5a-b6bc-c60d498847e9";
        String commit = "a".repeat(40);
        return new UiProjectionSnapshot(
                new UiProjectionSnapshot.Task("task-1", "customer-api", "pipeline-1", commit,
                        "b".repeat(64), "APPROVED", started, started.plusSeconds(60)),
                new UiProjectionSnapshot.WorkflowRun(runId, "ai-factory/task-1/pipeline-1", runId,
                        "task-1", "pipeline-1", commit, "APPROVED", started, started.plusSeconds(60)),
                List.of(new UiProjectionSnapshot.Delegation("developer-1", "workflow", runId,
                        "task-1", "pipeline-1", commit, "developer", "COMPLETED", 1_000, 2_000, 3)),
                evidence);
    }

    private static UiProjectionSnapshot.Evidence evidence(String artifactId) {
        String runId = "3d45f820-11d4-4e5a-b6bc-c60d498847e9";
        return new UiProjectionSnapshot.Evidence(artifactId, "ref-" + artifactId, runId,
                "task-1", "pipeline-1", "a".repeat(40), "tests",
                "evidence://task-1/pipeline-1/tests/" + "c".repeat(64), "c".repeat(64),
                "COMPLETE", "INTERNAL", 128);
    }
}
