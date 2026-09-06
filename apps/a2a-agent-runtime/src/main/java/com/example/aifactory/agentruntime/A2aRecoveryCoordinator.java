package com.example.aifactory.agentruntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** Reattaches durable tasks and drains the notification outbox after a process restart. */
public final class A2aRecoveryCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2aRecoveryCoordinator.class);
    private static final int BATCH_SIZE = 200;

    private final String role;
    private final A2aTaskStore store;
    private final AgentTaskWorkflowStarter workflowStarter;
    private final AgentTaskWorkflowControl workflowControl;
    private final A2aPushNotificationSender notificationSender;

    public A2aRecoveryCoordinator(String role, A2aTaskStore store, AgentTaskWorkflowStarter workflowStarter,
                                  AgentTaskWorkflowControl workflowControl,
                                  A2aPushNotificationSender notificationSender) {
        this.role = role;
        this.store = store;
        this.workflowStarter = workflowStarter;
        this.workflowControl = workflowControl;
        this.notificationSender = notificationSender;
    }

    public CompletionStage<Report> reconcile() {
        int cancellationsAcknowledged = 0;
        int cancellationsPending = 0;
        for (A2aTaskStore.PendingCancellation pending : store.pendingCancellations(role, BATCH_SIZE)) {
            try {
                workflowControl.requestCancellation(
                        pending.taskId(), pending.contextId(), pending.reason());
                store.acknowledgeCancellation(pending.cancellationId(), Instant.now());
                cancellationsAcknowledged++;
            } catch (RuntimeException failure) {
                cancellationsPending++;
                LOGGER.warn("A2A recovery retained cancellation taskId={}", pending.taskId());
            }
        }
        int workflows = 0;
        for (A2aTaskStore.StoredTask task : store.nonTerminal(role, BATCH_SIZE)) {
            AgentTaskWorkflowStarter.Execution execution = workflowStarter.start(submission(task), task.envelopeJson());
            store.recordWorkflowExecution(task.taskId(), execution.workflowId(), execution.runId());
            workflows++;
        }
        if (!notificationSender.enabled()) {
            return CompletableFuture.completedFuture(new Report(
                    workflows, 0, 0, cancellationsAcknowledged, cancellationsPending));
        }
        AtomicInteger acknowledged = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        List<CompletableFuture<Void>> deliveries = new ArrayList<>();
        for (A2aTaskStore.PendingNotification pending : store.pendingNotifications(role, BATCH_SIZE)) {
            A2aTaskStore.StoredTask task = store.find(pending.taskId())
                    .orElseThrow(() -> new IllegalStateException("Notification references an absent A2A task"));
            A2aPushNotificationSender.Notification notification = new A2aPushNotificationSender.Notification(
                    pending.role(), pending.taskId(), pending.contextId(), pending.sequence(), pending.state(),
                    pending.occurredAt(),
                    store.artifacts(task.taskId(), task.tenantId(), task.callerSubject()));
            CompletableFuture<Void> delivery = notificationSender.send(notification).handle((ack, failure) -> {
                if (failure == null) {
                    store.acknowledgeNotification(pending.notificationId(), Instant.now());
                    acknowledged.incrementAndGet();
                } else {
                    failed.incrementAndGet();
                    LOGGER.warn("A2A recovery retained unacknowledged notification taskId={}", pending.taskId());
                }
                return (Void) null;
            }).toCompletableFuture();
            deliveries.add(delivery);
        }
        int recovered = workflows;
        int recoveredCancellations = cancellationsAcknowledged;
        int retainedCancellations = cancellationsPending;
        return CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new Report(recovered, acknowledged.get(), failed.get(),
                        recoveredCancellations, retainedCancellations));
    }

    private static A2aSendMessageService.Submission submission(A2aTaskStore.StoredTask task) {
        return new A2aSendMessageService.Submission(task.taskId(), task.contextId(), task.messageId(), task.role(),
                task.skill(), task.callerSubject(), task.tenantId(), task.delegationId(), task.submittedAt(),
                null, null, task.businessTaskId(), task.workflowAttemptId());
    }

    public record Report(int workflowsReattached, int notificationsAcknowledged, int notificationsPending,
                         int cancellationsAcknowledged, int cancellationsPending) {}
}
