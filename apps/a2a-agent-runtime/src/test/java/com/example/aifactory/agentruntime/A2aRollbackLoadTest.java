package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises an A2A-compatible release rollback against durable state while concurrent tasks are in flight. */
class A2aRollbackLoadTest {
    private static final int TASKS_PER_STATE = 40;
    private static final String ROLE = "developer";
    private static final String TENANT = "tenant-a";
    private static final String CALLER = "orchestrator";
    private static final Instant STARTED = Instant.parse("2026-09-06T12:00:00Z");

    @Test
    void rollbackPreservesEveryTaskArtifactNotificationAndCancellationUnderLoad() throws Exception {
        DriverManagerDataSource dataSource = database();
        PostgresA2aTaskStore incidentRelease = store(dataSource);

        try (var executor = Executors.newFixedThreadPool(16)) {
            List<? extends java.util.concurrent.Future<?>> writes = IntStream.range(0, TASKS_PER_STATE * 5)
                    .mapToObj(index -> executor.submit(() -> seed(incidentRelease, index))).toList();
            for (java.util.concurrent.Future<?> write : writes) write.get();
        }

        // A new store instance represents the previous compatible A2A image after an in-place rollback.
        PostgresA2aTaskStore rollbackRelease = store(dataSource);
        Set<String> reattached = ConcurrentHashMap.newKeySet();
        Set<String> canceled = ConcurrentHashMap.newKeySet();
        AtomicInteger notifications = new AtomicInteger();
        A2aRecoveryCoordinator recovery = new A2aRecoveryCoordinator(ROLE, rollbackRelease,
                (submission, envelope) -> {
                    reattached.add(submission.taskId());
                    return new AgentTaskWorkflowStarter.Execution(
                            TemporalAgentTaskWorkflowGateway.workflowId(ROLE, submission.taskId()), "rollback-run");
                }, (taskId, contextId, reason) -> canceled.add(taskId), notificationSender(notifications));

        A2aRecoveryCoordinator.Report report = recovery.reconcile().toCompletableFuture().join();

        assertThat(report).isEqualTo(new A2aRecoveryCoordinator.Report(
                TASKS_PER_STATE * 3, TASKS_PER_STATE, 0, TASKS_PER_STATE, 0));
        assertThat(reattached).hasSize(TASKS_PER_STATE * 3);
        assertThat(canceled).hasSize(TASKS_PER_STATE);
        assertThat(notifications).hasValue(TASKS_PER_STATE);
        assertThat(rollbackRelease.pendingNotifications(ROLE, 1_000)).isEmpty();
        assertThat(rollbackRelease.pendingCancellations(ROLE, 1_000)).isEmpty();
        for (A2aSendMessageService.TaskState state : List.of(
                A2aSendMessageService.TaskState.SUBMITTED,
                A2aSendMessageService.TaskState.WORKING,
                A2aSendMessageService.TaskState.INPUT_REQUIRED,
                A2aSendMessageService.TaskState.COMPLETED,
                A2aSendMessageService.TaskState.CANCELED)) {
            assertThat(rollbackRelease.count(TENANT, CALLER, null, state)).isEqualTo(TASKS_PER_STATE);
        }
        IntStream.range(0, TASKS_PER_STATE).forEach(sequence -> assertThat(rollbackRelease.artifacts(
                taskId(3, sequence), TENANT, CALLER)).hasSize(1));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM a2a_agent_task", Integer.class))
                .isEqualTo(TASKS_PER_STATE * 5);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT message_id) FROM a2a_agent_task_message",
                Integer.class)).isEqualTo(TASKS_PER_STATE * 5);
    }

    private static void seed(PostgresA2aTaskStore store, int index) {
        int stateIndex = index / TASKS_PER_STATE;
        int sequence = index % TASKS_PER_STATE;
        String taskId = taskId(stateIndex, sequence);
        String messageId = "rollback-message-" + index;
        String contextId = "rollback-context-" + index;
        Instant now = STARTED.plusMillis(index);
        store.createOrGet(new A2aTaskStore.StoredTask(taskId, contextId, messageId, "a".repeat(64), ROLE,
                        "developer.code-task-v1", CALLER, TENANT, "delegation-" + index, now,
                        A2aSendMessageService.TaskState.SUBMITTED, 0, "{\"schema_version\":\"1\"}", null, null),
                new A2aTaskStore.HistoryRecord(messageId, "MESSAGE_ACCEPTED", now));
        if (stateIndex == 1) {
            transition(store, taskId, messageId, 0, A2aSendMessageService.TaskState.WORKING, now);
        } else if (stateIndex == 2) {
            transition(store, taskId, messageId, 0, A2aSendMessageService.TaskState.WORKING, now);
            transition(store, taskId, messageId, 1, A2aSendMessageService.TaskState.INPUT_REQUIRED, now);
        } else if (stateIndex == 3) {
            A2aTaskStore.StoredTask completed = transition(
                    store, taskId, messageId, 0, A2aSendMessageService.TaskState.COMPLETED, now);
            String digest = "%064x".formatted(index + 1);
            store.putArtifact(new A2aTaskStore.ArtifactRecord("artifact-" + index, taskId, TENANT, CALLER, digest,
                    Map.of("artifactId", "artifact-" + index, "digest", digest,
                            "uri", "evidence://" + taskId + "/attempt-1/output/" + digest)));
            store.enqueueNotification(new A2aTaskStore.PendingNotification(taskId + ":completed", taskId,
                    contextId, ROLE, completed.version(), completed.state(), now.plusSeconds(1)));
        } else if (stateIndex == 4) {
            A2aTaskStore.PendingCancellation cancellation = new A2aTaskStore.PendingCancellation(
                    taskId + ":cancel", taskId, contextId, ROLE, "A2A tasks/cancel", now.plusSeconds(1));
            store.requestCancellation(taskId, 0,
                    new A2aTaskStore.HistoryRecord(messageId, "TASK_CANCELED", now.plusSeconds(1)), cancellation)
                    .orElseThrow();
        }
    }

    private static A2aTaskStore.StoredTask transition(PostgresA2aTaskStore store, String taskId, String messageId,
                                                       long version, A2aSendMessageService.TaskState state,
                                                       Instant now) {
        return store.transition(taskId, version, state,
                new A2aTaskStore.HistoryRecord(messageId, "TASK_" + state.name(), now.plusSeconds(version + 1)))
                .orElseThrow();
    }

    private static String taskId(int stateIndex, int sequence) {
        return "rollback-" + stateIndex + '-' + sequence;
    }

    private static A2aPushNotificationSender notificationSender(AtomicInteger notifications) {
        return new A2aPushNotificationSender(new A2aPushNotificationProperties(true,
                URI.create("https://orchestrator.internal/a2a/notifications"), "/unused", 1, Duration.ZERO),
                new ObjectMapper(), (callback, body, signature) -> {
                    notifications.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(202);
                }, "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static DriverManagerDataSource database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:a2a-rollback-" + java.util.UUID.randomUUID()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(IntStream.rangeClosed(1, 6)
                .mapToObj(version -> new ClassPathResource("db/a2a-task-migration/V%03d__%s.sql".formatted(version,
                        switch (version) {
                            case 1 -> "create_a2a_task_projection";
                            case 2 -> "add_a2a_recovery_state";
                            case 3 -> "sequence_a2a_task_history";
                            case 4 -> "add_a2a_task_messages";
                            case 5 -> "add_a2a_cancellation_outbox";
                            case 6 -> "add_a2a_business_correlation";
                            default -> throw new IllegalStateException();
                        }))).toArray(org.springframework.core.io.Resource[]::new)).execute(dataSource);
        return dataSource;
    }

    private static PostgresA2aTaskStore store(DriverManagerDataSource dataSource) {
        return new PostgresA2aTaskStore(new JdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), new ObjectMapper());
    }
}
