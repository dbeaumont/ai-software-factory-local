package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic signal buffer with a bounded reconciliation timer. */
final class A2aTaskAwaiter {
    private final Map<String, A2aContracts.Notification> latest = new LinkedHashMap<>();

    void accept(A2aContracts.Notification notification) {
        if (notification == null) return;
        A2aContracts.Notification current = latest.get(notification.taskId());
        if (current == null || notification.sequence() > current.sequence()) {
            latest.put(notification.taskId(), notification);
        } else if (notification.sequence() == current.sequence() && !notification.equals(current)) {
            throw new SecurityException("Divergent A2A workflow notification replay");
        }
    }

    WaitResult awaitNext(String taskId, long afterSequence, Duration reconciliationInterval) {
        if (taskId == null || taskId.isBlank() || afterSequence < -1 || reconciliationInterval == null
                || reconciliationInterval.isZero() || reconciliationInterval.isNegative()) {
            throw new IllegalArgumentException("A2A workflow wait parameters are invalid");
        }
        boolean received = Workflow.await(reconciliationInterval, () -> {
            A2aContracts.Notification value = latest.get(taskId);
            return value != null && value.sequence() > afterSequence;
        });
        return received ? new WaitResult(latest.get(taskId), false) : new WaitResult(null, true);
    }

    record WaitResult(A2aContracts.Notification notification, boolean reconciliationDue) {}
}
