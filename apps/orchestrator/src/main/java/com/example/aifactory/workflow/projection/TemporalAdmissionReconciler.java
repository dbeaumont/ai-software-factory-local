package com.example.aifactory.workflow.projection;

import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.TaskMemory;
import com.example.aifactory.workflow.WorkflowCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Repairs a crash between durable task admission and deterministic Temporal workflow start. */
@Component
public final class TemporalAdmissionReconciler {
    private static final Logger log = LoggerFactory.getLogger(TemporalAdmissionReconciler.class);
    private static final int BATCH_SIZE = 100;
    private final TaskMemory memory;
    private final WorkflowCoordinator coordinator;
    private final AtomicBoolean running = new AtomicBoolean();

    public TemporalAdmissionReconciler(TaskMemory memory, WorkflowCoordinator coordinator) {
        this.memory = memory;
        this.coordinator = coordinator;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "${ai-factory.temporal.admission-reconciliation-delay:PT10S}")
    public void reconcile() {
        if (!running.compareAndSet(false, true)) return;
        try {
            for (TaskState task : memory.pendingAdmissions(BATCH_SIZE)) reconcile(task);
        } finally {
            running.set(false);
        }
    }

    private void reconcile(TaskState task) {
        try {
            coordinator.start(task);
            memory.workflowStarted(task);
            log.info("Temporal admission reconciled for task {} attempt {}", task.id, task.workflowAttemptId);
        } catch (RuntimeException failure) {
            memory.admissionFailed(task, failure);
            log.warn("Temporal admission reconciliation deferred for task {} attempt {} ({})",
                    task.id, task.workflowAttemptId, failure.getClass().getSimpleName());
        }
    }
}
