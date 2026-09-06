package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;

import java.time.Duration;
import java.util.List;

/** Deterministic, bounded cancellation propagation preserving already published Evidence references. */
final class A2aCancellationCoordinator {
    private static final int MAX_CONFIRMATION_CYCLES = 3;
    private final A2aTaskAwaiter awaiter;

    A2aCancellationCoordinator(A2aTaskAwaiter awaiter) { this.awaiter = awaiter; }

    Result cancel(String agentRole, A2aContracts.TaskSnapshot current, A2aActivities.CancelTask cancelTask,
                  A2aActivities.GetTask getTask, Duration confirmationInterval) {
        List<String> evidence = evidence(current);
        A2aContracts.TaskSnapshot observed;
        try {
            observed = cancelTask.cancelTask(new A2aContracts.TaskQuery(agentRole, current.taskId(), 50));
        } catch (RuntimeException unreachable) {
            return new Result(Status.RECONCILIATION_REQUIRED, current, evidence);
        }
        if (observed.state() == A2aContracts.TaskState.CANCELED) {
            return new Result(Status.CONFIRMED, observed, merge(evidence, evidence(observed)));
        }
        awaiter.seed(agentRole, observed);
        long sequence = ((Number) observed.metadata().getOrDefault("sequence", 0)).longValue();
        for (int cycle = 0; cycle < MAX_CONFIRMATION_CYCLES; cycle++) {
            A2aTaskAwaiter.WaitResult wait = awaiter.awaitNext(current.taskId(), sequence, confirmationInterval);
            if (wait.reconciliationDue()) {
                try {
                    A2aContracts.TaskSnapshot polled = getTask.getTask(
                            new A2aContracts.TaskQuery(agentRole, current.taskId(), 50));
                    awaiter.applyReconciliation(agentRole, polled);
                    observed = polled;
                } catch (RuntimeException unreachable) {
                    return new Result(Status.RECONCILIATION_REQUIRED, observed, merge(evidence, evidence(observed)));
                }
            } else {
                A2aContracts.Notification notification = wait.notification();
                observed = new A2aContracts.TaskSnapshot(notification.taskId(), notification.contextId(),
                        notification.state(), notification.occurredAt(), notification.artifacts(),
                        java.util.Map.of("sequence", notification.sequence()));
            }
            evidence = merge(evidence, evidence(observed));
            sequence = ((Number) observed.metadata().getOrDefault("sequence", sequence)).longValue();
            if (observed.state() == A2aContracts.TaskState.CANCELED) {
                return new Result(Status.CONFIRMED, observed, evidence);
            }
            if (terminal(observed.state())) return new Result(Status.REJECTED, observed, evidence);
        }
        return new Result(Status.RECONCILIATION_REQUIRED, observed, evidence);
    }

    private static List<String> evidence(A2aContracts.TaskSnapshot task) {
        return task.artifacts().stream().flatMap(artifact -> artifact.parts().stream())
                .filter(part -> part.uri() != null && "evidence".equals(part.uri().getScheme()))
                .map(part -> part.uri().toString()).distinct().sorted().toList();
    }

    private static List<String> merge(List<String> left, List<String> right) {
        return java.util.stream.Stream.concat(left.stream(), right.stream()).distinct().sorted().toList();
    }

    private static boolean terminal(A2aContracts.TaskState state) {
        return switch (state) {
            case COMPLETED, REJECTED, FAILED, CANCELED -> true;
            default -> false;
        };
    }

    enum Status { CONFIRMED, REJECTED, RECONCILIATION_REQUIRED }

    record Result(Status status, A2aContracts.TaskSnapshot task, List<String> evidenceUris) {
        Result { evidenceUris = List.copyOf(evidenceUris); }
    }
}
