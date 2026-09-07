package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class A2aRecoveryLifecycleTest {

    @Test
    void retriesPendingNotificationsAfterTheReceiverBecomesAvailable() throws Exception {
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        Instant now = Instant.parse("2026-09-07T02:40:00Z");
        store.createOrGet(new A2aTaskStore.StoredTask(
                "task-1", "context-1", "message-1", "a".repeat(64), "developer",
                "developer.code-task-v1", "orchestrator", "tenant-a", "delegation-1", now,
                A2aSendMessageService.TaskState.COMPLETED, 1, "{}", "workflow-1", "run-1"),
                new A2aTaskStore.HistoryRecord("message-1", "TASK_COMPLETED", now));
        store.enqueueNotification(new A2aTaskStore.PendingNotification(
                "notification-1", "task-1", "context-1", "developer", 1,
                A2aSendMessageService.TaskState.COMPLETED, now));
        AtomicInteger deliveries = new AtomicInteger();
        CompletableFuture<Void> recovered = new CompletableFuture<>();
        A2aPushNotificationSender sender = new A2aPushNotificationSender(
                new A2aPushNotificationProperties(true,
                        URI.create("https://orchestrator.internal/a2a/notifications"), "/unused", 1, Duration.ZERO),
                new ObjectMapper(), (callback, body, signature) -> {
                    if (deliveries.incrementAndGet() == 1) return CompletableFuture.completedFuture(503);
                    recovered.complete(null);
                    return CompletableFuture.completedFuture(202);
                }, "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        A2aRecoveryCoordinator coordinator = new A2aRecoveryCoordinator("developer", store,
                (submission, envelope) -> { throw new AssertionError("No active workflow expected"); },
                (taskId, contextId, reason) -> { }, sender);
        A2aRecoveryLifecycle lifecycle = new A2aRecoveryLifecycle(coordinator, Duration.ofMillis(10));

        try {
            lifecycle.start();
            recovered.get(2, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (!store.pendingNotifications("developer", 10).isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(store.pendingNotifications("developer", 10)).isEmpty();
            assertThat(deliveries).hasValueGreaterThanOrEqualTo(2);
        } finally {
            lifecycle.stop();
        }
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
