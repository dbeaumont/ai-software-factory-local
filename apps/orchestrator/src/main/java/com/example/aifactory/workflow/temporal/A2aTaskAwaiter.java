package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.workflow.Workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    A2aContracts.Notification awaitUntilTerminal(String agentRole, A2aContracts.TaskSnapshot initial,
                                                  Duration reconciliationInterval, A2aActivities.GetTask getTask) {
        seed(agentRole, initial);
        long sequence = latest.get(initial.taskId()).sequence();
        if (terminal(latest.get(initial.taskId()).state())) return latest.get(initial.taskId());
        while (true) {
            WaitResult wait = awaitNext(initial.taskId(), sequence, reconciliationInterval);
            if (wait.reconciliationDue()) {
                A2aContracts.TaskSnapshot observed = getTask.getTask(
                        new A2aContracts.TaskQuery(agentRole, initial.taskId(), 50));
                applyReconciliation(agentRole, observed);
            }
            A2aContracts.Notification current = latest.get(initial.taskId());
            if (current != null && current.sequence() > sequence) {
                sequence = current.sequence();
                if (terminal(current.state())) return current;
            }
        }
    }

    void seed(String agentRole, A2aContracts.TaskSnapshot task) {
        if (!latest.containsKey(task.taskId())) {
            long sequence = number(task.metadata().getOrDefault("sequence", 0));
            accept(new A2aContracts.Notification(agentRole, task.taskId(), task.contextId(), sequence,
                    task.state(), task.updatedAt(), task.artifacts(), Map.of("source", "dispatch")));
        }
    }

    void applyReconciliation(String agentRole, A2aContracts.TaskSnapshot task) {
        A2aContracts.Notification current = latest.get(task.taskId());
        long sequence = current == null ? -1 : current.sequence();
        List<A2aContracts.Notification> transitions = transitions(agentRole, task);
        if (transitions.isEmpty()) {
            long observed = number(task.metadata().get("sequence"));
            if (observed > sequence) transitions = List.of(new A2aContracts.Notification(
                    agentRole, task.taskId(), task.contextId(), observed, task.state(), task.updatedAt(),
                    task.artifacts(), Map.of("source", "getTask")));
        }
        for (A2aContracts.Notification transition : transitions.stream()
                .sorted(Comparator.comparingLong(A2aContracts.Notification::sequence)).toList()) {
            if (transition.sequence() <= sequence) continue;
            if (transition.sequence() != sequence + 1) {
                throw new IllegalStateException("A2A reconciliation history contains a sequence gap");
            }
            accept(transition);
            sequence = transition.sequence();
        }
    }

    private static List<A2aContracts.Notification> transitions(String agentRole, A2aContracts.TaskSnapshot task) {
        Object raw = task.metadata().get("transitions");
        if (!(raw instanceof List<?> values)) return List.of();
        List<A2aContracts.Notification> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> transition)) {
                throw new IllegalArgumentException("A2A reconciliation transition is malformed");
            }
            long sequence = number(transition.get("sequence"));
            String state = String.valueOf(transition.get("state")).replace("TASK_STATE_", "");
            Instant occurredAt = Instant.parse(String.valueOf(transition.get("occurredAt")));
            A2aContracts.TaskState taskState = A2aContracts.TaskState.valueOf(state);
            result.add(new A2aContracts.Notification(agentRole, task.taskId(), task.contextId(), sequence,
                    taskState, occurredAt, taskState == task.state() ? task.artifacts() : List.of(),
                    Map.of("source", "getTask")));
        }
        return List.copyOf(result);
    }

    private static long number(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0) {
            throw new IllegalArgumentException("A2A task sequence is missing or invalid");
        }
        return number.longValue();
    }

    private static boolean terminal(A2aContracts.TaskState state) {
        return switch (state) {
            case COMPLETED, REJECTED, FAILED, CANCELED -> true;
            default -> false;
        };
    }

    record WaitResult(A2aContracts.Notification notification, boolean reconciliationDue) {}
}
