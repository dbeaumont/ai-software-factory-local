package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aRecoveryCoordinatorTest {

    @Test
    void reattachesNonTerminalWorkflowAndAcknowledgesDurableNotification() {
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        A2aTaskStore.StoredTask task = new A2aTaskStore.StoredTask(
                "task-1", "context-1", "message-1", "a".repeat(64), "developer",
                "developer.code-task-v1", "orchestrator", "tenant-a", "delegation-1", now,
                A2aSendMessageService.TaskState.WORKING, 1, "{\"target_role\":\"developer\"}",
                "a2a-agent-task-v1/developer/task-1", "old-run");
        store.createOrGet(task, new A2aTaskStore.HistoryRecord("message-1", "MESSAGE_ACCEPTED", now));
        store.enqueueNotification(new A2aTaskStore.PendingNotification(
                "task-1:working", "task-1", "context-1", "developer", 1,
                A2aSendMessageService.TaskState.WORKING, now));
        List<String> starts = new ArrayList<>();
        AgentTaskWorkflowStarter starter = (submission, envelope) -> {
            starts.add(submission.taskId() + envelope);
            return new AgentTaskWorkflowStarter.Execution(
                    "a2a-agent-task-v1/developer/" + submission.taskId(), "recovered-run");
        };
        A2aPushNotificationSender sender = new A2aPushNotificationSender(
                new A2aPushNotificationProperties(true, URI.create("https://orchestrator.internal/a2a/notifications"),
                        "/unused", 1, Duration.ZERO), new ObjectMapper(),
                (callback, body, signature) -> java.util.concurrent.CompletableFuture.completedFuture(202),
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        A2aRecoveryCoordinator.Report report = new A2aRecoveryCoordinator(
                "developer", store, starter, sender).reconcile().toCompletableFuture().join();

        assertThat(starts).containsExactly("task-1{\"target_role\":\"developer\"}");
        assertThat(store.find("task-1").orElseThrow().workflowRunId()).isEqualTo("recovered-run");
        assertThat(store.pendingNotifications("developer", 10)).isEmpty();
        assertThat(report).isEqualTo(new A2aRecoveryCoordinator.Report(1, 1, 0));
    }

    @Test
    void retriesTemporalRecoveryAfterOutageWithoutDuplicatingTheDurableTask() {
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        store.createOrGet(new A2aTaskStore.StoredTask(
                "task-temporal", "context-1", "message-1", "a".repeat(64), "developer",
                "developer.code-task-v1", "orchestrator", "tenant-a", "delegation-1", now,
                A2aSendMessageService.TaskState.SUBMITTED, 0, "{\"target_role\":\"developer\"}", null, null),
                new A2aTaskStore.HistoryRecord("message-1", "MESSAGE_ACCEPTED", now));
        AtomicInteger attempts = new AtomicInteger();
        AgentTaskWorkflowStarter recoveringStarter = (submission, envelope) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new A2aOperationalException(
                        A2aOperationalException.Category.DEPENDENCY, "Temporal unavailable", null);
            }
            return new AgentTaskWorkflowStarter.Execution(
                    "a2a-agent-task-v1/developer/" + submission.taskId(), "recovered-run");
        };
        A2aRecoveryCoordinator coordinator = new A2aRecoveryCoordinator(
                "developer", store, recoveringStarter, disabledSender());

        assertThatThrownBy(coordinator::reconcile).isInstanceOf(A2aOperationalException.class);
        A2aRecoveryCoordinator.Report recovered = coordinator.reconcile().toCompletableFuture().join();

        assertThat(recovered.workflowsReattached()).isEqualTo(1);
        assertThat(attempts).hasValue(2);
        assertThat(store.nonTerminal("developer", 10)).hasSize(1);
        assertThat(store.find("task-temporal").orElseThrow().workflowRunId()).isEqualTo("recovered-run");
    }

    private static A2aPushNotificationSender disabledSender() {
        return new A2aPushNotificationSender(
                new A2aPushNotificationProperties(false, null, null, 1, Duration.ZERO), new ObjectMapper(),
                (callback, body, signature) -> java.util.concurrent.CompletableFuture.completedFuture(202),
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
