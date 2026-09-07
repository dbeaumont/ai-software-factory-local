package com.example.aifactory.a2a;

import com.example.aifactory.workflow.projection.PostgresA2aTaskAssociationStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class A2aTaskAssociationStoreTest {
    private static final Path DATABASE = Path.of(System.getProperty(
            "multiagent.database.directory", "../../resources/multiagents/database"));
    @Test
    void persistsServerGeneratedIdsUnderTheBusinessDelegationKeyIdempotently() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        PostgresA2aTaskAssociationStore store = new PostgresA2aTaskAssociationStore(jdbc);
        store.prepareDelegation(context(), intent());
        store.record(context(), "message-1", "b".repeat(64), "server-task-a8b4", "server-context-92ef");
    }

    @Test
    void rejectsMissingOrDivergentDelegationLineageBeforeDispatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        PostgresA2aTaskAssociationStore store = new PostgresA2aTaskAssociationStore(jdbc);

        assertThatThrownBy(() -> store.prepareDelegation(context(), intent()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("delegation lineage");
    }

    @Test
    void rejectsMissingServerIdsAndDivergentReplay() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PostgresA2aTaskAssociationStore store = new PostgresA2aTaskAssociationStore(jdbc);
        assertThatThrownBy(() -> store.record(context(), "message-1", "b".repeat(64), "", "context"))
                .isInstanceOf(IllegalArgumentException.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> store.record(context(), "message-1", "b".repeat(64),
                "other-task", "other-context"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void migrationNeverUsesA2aIdentifiersAsBusinessPrimaryKey() throws Exception {
        String migration = Files.readString(DATABASE.resolve("V016__a2a_task_associations.sql"));
        assertThat(migration).contains("delegation_id      varchar(128) PRIMARY KEY")
                .contains("a2a_task_id        varchar(255) NOT NULL")
                .doesNotContain("a2a_task_id        varchar(255) PRIMARY KEY");
        String extension = Files.readString(DATABASE.resolve("V017__complete_a2a_task_correlation.sql"));
        assertThat(extension).contains("workflow_id varchar(255)", "workflow_run_id varchar(128)",
                "message_id varchar(200)", "agent_card_digest char(64)");
    }

    private static A2aExecutionContext context() {
        return new A2aExecutionContext("1", "task-1", "attempt-1", "workflow-1", "run-1", "customer-api",
                "a".repeat(40), "delegation-1", null, "developer", List.of("b".repeat(64)));
    }

    private static A2aTaskAssociationStore.DispatchIntent intent() {
        return new A2aTaskAssociationStore.DispatchIntent("c".repeat(64), 12_000, 5_000_000, 6);
    }
}
