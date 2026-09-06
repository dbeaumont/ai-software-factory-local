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
    @Test
    void persistsServerGeneratedIdsUnderTheBusinessDelegationKeyIdempotently() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        PostgresA2aTaskAssociationStore store = new PostgresA2aTaskAssociationStore(jdbc);
        store.record(context(), "server-task-a8b4", "server-context-92ef");
    }

    @Test
    void rejectsMissingServerIdsAndDivergentReplay() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PostgresA2aTaskAssociationStore store = new PostgresA2aTaskAssociationStore(jdbc);
        assertThatThrownBy(() -> store.record(context(), "", "context")).isInstanceOf(IllegalArgumentException.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        assertThatThrownBy(() -> store.record(context(), "other-task", "other-context"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void migrationNeverUsesA2aIdentifiersAsBusinessPrimaryKey() throws Exception {
        String migration = Files.readString(Path.of(
                "../../resources/multiagents/database/V016__a2a_task_associations.sql"));
        assertThat(migration).contains("delegation_id      varchar(128) PRIMARY KEY")
                .contains("a2a_task_id        varchar(255) NOT NULL")
                .doesNotContain("a2a_task_id        varchar(255) PRIMARY KEY");
    }

    private static A2aExecutionContext context() {
        return new A2aExecutionContext("1", "task-1", "attempt-1", "workflow-1", "run-1", "customer-api",
                "a".repeat(40), "delegation-1", null, "developer", List.of("b".repeat(64)));
    }
}
