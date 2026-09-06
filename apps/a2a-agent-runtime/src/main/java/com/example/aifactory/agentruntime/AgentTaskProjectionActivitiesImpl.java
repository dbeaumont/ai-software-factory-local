package com.example.aifactory.agentruntime;

import java.time.Instant;

/** Idempotent activity writing only the application-owned A2A projection. */
public final class AgentTaskProjectionActivitiesImpl implements AgentTaskProjectionActivities {
    private final A2aTaskStore store;

    public AgentTaskProjectionActivitiesImpl(A2aTaskStore store) { this.store = store; }

    @Override
    public void project(Projection projection) {
        if (projection == null || projection.taskId() == null || projection.taskId().isBlank()
                || projection.transitionId() == null || projection.transitionId().isBlank()) {
            throw new IllegalArgumentException("A2A projection command is incomplete");
        }
        A2aSendMessageService.TaskState desired = A2aSendMessageService.TaskState.valueOf(projection.state());
        while (true) {
            A2aTaskStore.StoredTask current = store.find(projection.taskId())
                    .orElseThrow(() -> new IllegalStateException("A2A task projection is absent"));
            if (current.state() == desired) {
                enqueue(projection, current);
                return;
            }
            if (current.state().terminal()) {
                throw new IllegalStateException("Cannot project over terminal A2A state " + current.state());
            }
            java.util.Optional<A2aTaskStore.StoredTask> updated = store.transition(current.taskId(), current.version(), desired,
                    new A2aTaskStore.HistoryRecord(current.messageId(),
                            "WORKFLOW_" + desired.name(), Instant.now()));
            if (updated.isPresent()) {
                enqueue(projection, updated.get());
                return;
            }
        }
    }

    private void enqueue(Projection projection, A2aTaskStore.StoredTask task) {
        store.enqueueNotification(new A2aTaskStore.PendingNotification(
                projection.transitionId(), task.taskId(), task.contextId(), task.role(), task.version(),
                task.state(), Instant.now()));
    }
}
