package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresA2aTaskStoreTest {
    private JdbcTemplate jdbc;
    private PostgresA2aTaskStore store;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:a2a-" + java.util.UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/a2a-task-migration/V001__create_a2a_task_projection.sql"), new ClassPathResource(
                "db/a2a-task-migration/V002__add_a2a_recovery_state.sql"), new ClassPathResource(
                "db/a2a-task-migration/V003__sequence_a2a_task_history.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        store = new PostgresA2aTaskStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
    }

    @Test
    void persistsIdempotencyHistoryAclArtifactsAndOptimisticVersion() {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        A2aTaskStore.StoredTask task = new A2aTaskStore.StoredTask(
                "task-1", "context-1", "message-1", "a".repeat(64), "developer",
                "developer.code-task-v1", "orchestrator", "tenant-a", "delegation-1", now,
                A2aSendMessageService.TaskState.SUBMITTED, 0, "{\"target_role\":\"developer\"}", null, null);
        A2aTaskStore.HistoryRecord accepted = new A2aTaskStore.HistoryRecord(
                "message-1", "MESSAGE_ACCEPTED", now);

        assertThat(store.createOrGet(task, accepted).created()).isTrue();
        assertThat(store.createOrGet(task, accepted).created()).isFalse();
        assertThat(store.history("task-1", 50)).hasSize(1);

        A2aTaskStore.StoredTask transitioned = store.transition("task-1", 0,
                A2aSendMessageService.TaskState.WORKING,
                new A2aTaskStore.HistoryRecord("message-1", "TASK_WORKING", now.plusSeconds(1)))
                .orElseThrow();
        assertThat(transitioned.version()).isEqualTo(1);
        assertThat(store.transition("task-1", 0, A2aSendMessageService.TaskState.FAILED,
                new A2aTaskStore.HistoryRecord("message-1", "TASK_FAILED", now))).isEmpty();
        assertThat(store.history("task-1", 50)).hasSize(2);

        store.recordWorkflowExecution("task-1", "a2a-agent-task-v1/developer/task-1", "run-1");
        assertThat(store.nonTerminal("developer", 10)).singleElement()
                .satisfies(recovered -> {
                    assertThat(recovered.envelopeJson()).contains("developer");
                    assertThat(recovered.workflowId()).isEqualTo("a2a-agent-task-v1/developer/task-1");
                    assertThat(recovered.workflowRunId()).isEqualTo("run-1");
                });
        A2aTaskStore.PendingNotification pending = new A2aTaskStore.PendingNotification(
                "task-1:working", "task-1", "context-1", "developer", 1,
                A2aSendMessageService.TaskState.WORKING, now.plusSeconds(1));
        store.enqueueNotification(pending);
        store.enqueueNotification(pending);
        assertThat(store.pendingNotifications("developer", 10)).containsExactly(pending);
        store.acknowledgeNotification(pending.notificationId(), now.plusSeconds(2));
        assertThat(store.pendingNotifications("developer", 10)).isEmpty();

        A2aTaskStore.ArtifactRecord artifact = new A2aTaskStore.ArtifactRecord(
                "artifact-1", "task-1", "tenant-a", "orchestrator", "b".repeat(64),
                Map.of("artifactId", "artifact-1"));
        store.putArtifact(artifact);
        store.putArtifact(artifact);
        assertThat(store.artifacts("task-1", "tenant-a", "orchestrator"))
                .containsExactly(Map.of("artifactId", "artifact-1"));
        assertThat(store.artifacts("task-1", "tenant-b", "orchestrator")).isEmpty();
    }
}
