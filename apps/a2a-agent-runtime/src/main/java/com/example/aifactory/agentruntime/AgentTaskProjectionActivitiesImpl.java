package com.example.aifactory.agentruntime;

import java.time.Instant;

/** Idempotent activity writing only the application-owned A2A projection. */
public final class AgentTaskProjectionActivitiesImpl implements AgentTaskProjectionActivities {
    private final A2aTaskStore store;
    private final A2aSpanLinks spanLinks;
    private final A2aServerMetrics metrics;

    public AgentTaskProjectionActivitiesImpl(A2aTaskStore store) {
        this(store, A2aSpanLinks.disabled(), A2aServerMetrics.disabled());
    }

    AgentTaskProjectionActivitiesImpl(A2aTaskStore store, A2aSpanLinks spanLinks, A2aServerMetrics metrics) {
        this.store = store;
        this.spanLinks = spanLinks;
        this.metrics = metrics;
    }

    @Override
    public void project(Projection projection) {
        if (projection == null || projection.taskId() == null || projection.taskId().isBlank()
                || projection.transitionId() == null || projection.transitionId().isBlank()) {
            throw new IllegalArgumentException("A2A projection command is incomplete");
        }
        spanLinks.run("ai.factory.a2a.agent.workflow", "task-to-agent-workflow", projection.traceparent(),
                java.util.Map.of("a2a.task.id", projection.taskId(), "a2a.task.state", projection.state()),
                () -> projectDurably(projection));
    }

    private void projectDurably(Projection projection) {
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
                metrics.transition(updated.get(), desired, Instant.now());
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
