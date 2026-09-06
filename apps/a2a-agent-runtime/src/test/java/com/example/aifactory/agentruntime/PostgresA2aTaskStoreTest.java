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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
                "db/a2a-task-migration/V003__sequence_a2a_task_history.sql"), new ClassPathResource(
                "db/a2a-task-migration/V004__add_a2a_task_messages.sql"), new ClassPathResource(
                "db/a2a-task-migration/V005__add_a2a_cancellation_outbox.sql")).execute(dataSource);
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

        A2aTaskStore.StoredTask inputRequired = store.transition("task-1", 1,
                A2aSendMessageService.TaskState.INPUT_REQUIRED,
                new A2aTaskStore.HistoryRecord("message-1", "TASK_INPUT_REQUIRED", now.plusSeconds(2)))
                .orElseThrow();
        A2aTaskStore.ContinueResult continued = store.continueTask("task-1", "context-1", "message-2",
                "b".repeat(64), "{\"decision\":\"approved\"}",
                new A2aTaskStore.HistoryRecord("message-2", "MESSAGE_CONTINUED", now.plusSeconds(3)));
        assertThat(inputRequired.state()).isEqualTo(A2aSendMessageService.TaskState.INPUT_REQUIRED);
        assertThat(continued.accepted()).isTrue();
        assertThat(continued.task().state()).isEqualTo(A2aSendMessageService.TaskState.WORKING);
        assertThat(store.continueTask("task-1", "context-1", "message-2", "b".repeat(64),
                "{\"decision\":\"approved\"}",
                new A2aTaskStore.HistoryRecord("message-2", "MESSAGE_CONTINUED", now.plusSeconds(3))).accepted())
                .isFalse();
        assertThat(store.findByMessageId("message-2")).contains(continued.task());

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

        A2aTaskStore.PendingCancellation cancellation = new A2aTaskStore.PendingCancellation(
                "task-1:cancel", "task-1", "context-1", "developer", "A2A tasks/cancel",
                now.plusSeconds(4));
        assertThat(store.requestCancellation("task-1", 3,
                new A2aTaskStore.HistoryRecord("message-1", "TASK_CANCELED", now.plusSeconds(4)), cancellation))
                .get().extracting(A2aTaskStore.StoredTask::state)
                .isEqualTo(A2aSendMessageService.TaskState.CANCELED);
        assertThat(store.pendingCancellations("developer", 10)).containsExactly(cancellation);
        store.acknowledgeCancellation(cancellation.cancellationId(), now.plusSeconds(5));
        assertThat(store.pendingCancellations("developer", 10)).isEmpty();
    }

    @Test
    void serializesConcurrentAdmissionAndTerminalCancellationRaces() throws Exception {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        A2aTaskStore.StoredTask task = new A2aTaskStore.StoredTask(
                "task-race", "context-race", "message-race", "a".repeat(64), "developer",
                "developer.code-task-v1", "orchestrator", "tenant-a", "delegation-1", now,
                A2aSendMessageService.TaskState.SUBMITTED, 0, "{}", null, null);
        A2aTaskStore.HistoryRecord accepted = new A2aTaskStore.HistoryRecord(
                "message-race", "MESSAGE_ACCEPTED", now);
        int contenders = 12;
        CyclicBarrier admissionBarrier = new CyclicBarrier(contenders);

        try (var executor = Executors.newFixedThreadPool(contenders)) {
            List<Future<A2aTaskStore.CreateResult>> admissions = java.util.stream.IntStream.range(0, contenders)
                    .mapToObj(ignored -> executor.submit(() -> {
                        admissionBarrier.await();
                        return store.createOrGet(task, accepted);
                    })).toList();
            List<A2aTaskStore.CreateResult> results = new java.util.ArrayList<>();
            for (Future<A2aTaskStore.CreateResult> admission : admissions) results.add(admission.get());
            assertThat(results).filteredOn(A2aTaskStore.CreateResult::created).hasSize(1);
            assertThat(results).extracting(result -> result.task().taskId()).containsOnly("task-race");
        }
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM a2a_agent_task WHERE message_id='message-race'", Integer.class)).isEqualTo(1);
        assertThat(store.history("task-race", 50)).hasSize(1);

        CyclicBarrier terminalBarrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<java.util.Optional<A2aTaskStore.StoredTask>> canceled = executor.submit(() -> {
                terminalBarrier.await();
                return store.transition("task-race", 0, A2aSendMessageService.TaskState.CANCELED,
                        new A2aTaskStore.HistoryRecord("message-race", "TASK_CANCELED", now.plusSeconds(1)));
            });
            Future<java.util.Optional<A2aTaskStore.StoredTask>> completed = executor.submit(() -> {
                terminalBarrier.await();
                return store.transition("task-race", 0, A2aSendMessageService.TaskState.COMPLETED,
                        new A2aTaskStore.HistoryRecord("message-race", "TASK_COMPLETED", now.plusSeconds(1)));
            });
            assertThat(List.of(canceled.get(), completed.get())).filteredOn(java.util.Optional::isPresent).hasSize(1);
        }
        assertThat(store.find("task-race").orElseThrow().state())
                .isIn(A2aSendMessageService.TaskState.CANCELED, A2aSendMessageService.TaskState.COMPLETED);
        assertThat(store.history("task-race", 50)).hasSize(2);
    }
}
