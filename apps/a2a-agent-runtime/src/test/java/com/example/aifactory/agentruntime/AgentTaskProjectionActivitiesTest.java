package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskProjectionActivitiesTest {

    @Test
    void retryAfterCommittedProjectionIsAnIdempotentNoOp() {
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        A2aTaskStore.StoredTask task = new A2aTaskStore.StoredTask(
                "task-1", "context-1", "message-1", "a".repeat(64), "developer",
                "developer.code-task-v1", "orchestrator", "tenant-a", "delegation-1", now,
                A2aSendMessageService.TaskState.SUBMITTED, 0);
        store.createOrGet(task, new A2aTaskStore.HistoryRecord("message-1", "MESSAGE_ACCEPTED", now));
        AgentTaskProjectionActivitiesImpl activities = new AgentTaskProjectionActivitiesImpl(store);
        AgentTaskProjectionActivities.Projection command = new AgentTaskProjectionActivities.Projection(
                "task-1", "WORKING", "task-1:working");

        activities.project(command);
        activities.project(command);

        assertThat(store.find("task-1").orElseThrow().state())
                .isEqualTo(A2aSendMessageService.TaskState.WORKING);
        assertThat(store.find("task-1").orElseThrow().version()).isEqualTo(1);
        assertThat(store.history("task-1", 50)).hasSize(2);
    }
}
