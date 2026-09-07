package com.example.aifactory.agentruntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Periodically reconciles durable workflows, cancellations and notification outbox entries. */
final class A2aRecoveryLifecycle implements SmartLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2aRecoveryLifecycle.class);

    private final A2aRecoveryCoordinator coordinator;
    private final Duration interval;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    A2aRecoveryLifecycle(A2aRecoveryCoordinator coordinator, Duration interval) {
        if (coordinator == null || interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("A2A recovery lifecycle configuration is invalid");
        }
        this.coordinator = coordinator;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running) return;
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("a2a-recovery-", 0).factory());
        scheduler.scheduleWithFixedDelay(this::reconcile, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void reconcile() {
        if (!running) return;
        try {
            A2aRecoveryCoordinator.Report report = coordinator.reconcile().toCompletableFuture().join();
            if (report.workflowsReattached() > 0 || report.notificationsAcknowledged() > 0
                    || report.notificationsPending() > 0 || report.cancellationsAcknowledged() > 0
                    || report.cancellationsPending() > 0) {
                LOGGER.info("A2A recovery pass workflows={} notificationsAcknowledged={} "
                                + "notificationsPending={} cancellationsAcknowledged={} cancellationsPending={}",
                        report.workflowsReattached(), report.notificationsAcknowledged(),
                        report.notificationsPending(), report.cancellationsAcknowledged(),
                        report.cancellationsPending());
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("A2A recovery pass failed; durable entries remain pending for retry");
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override public boolean isRunning() { return running; }
    @Override public int getPhase() { return 100; }
}
