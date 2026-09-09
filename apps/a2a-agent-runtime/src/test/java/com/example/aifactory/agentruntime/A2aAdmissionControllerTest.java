package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aAdmissionControllerTest {

    @Test
    void enforcesTenantQuotaButAlwaysAllowsAnIdempotentReplay() {
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        A2aAdmissionController admission = new A2aAdmissionController(store,
                new AgentConcurrencyProperties(1, 1, 2, 2, 1, 2, Duration.ofSeconds(5),
                        Duration.ofSeconds(5)));
        AtomicInteger creates = new AtomicInteger();

        A2aTaskStore.CreateResult first = admission.admit("message-1", "developer", "tenant-a",
                () -> create(store, "task-1", "message-1", "tenant-a", creates));
        A2aTaskStore.CreateResult replay = admission.admit("message-1", "developer", "tenant-a",
                () -> create(store, "task-replay", "message-1", "tenant-a", creates));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThatThrownBy(() -> admission.admit("message-2", "developer", "tenant-a",
                () -> create(store, "task-2", "message-2", "tenant-a", creates)))
                .isInstanceOf(A2aOperationalException.class)
                .extracting(failure -> ((A2aOperationalException) failure).category())
                .isEqualTo(A2aOperationalException.Category.QUOTA);
        assertThat(creates).hasValue(2);
    }

    private static A2aTaskStore.CreateResult create(InMemoryA2aTaskStore store, String taskId, String messageId,
                                                     String tenantId, AtomicInteger creates) {
        creates.incrementAndGet();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return store.createOrGet(new A2aTaskStore.StoredTask(
                taskId, "context-1", messageId, "a".repeat(64), "developer", "developer.code-task-v1",
                "orchestrator", tenantId, "delegation-1", now, A2aSendMessageService.TaskState.SUBMITTED,
                0, "{}", null, null), new A2aTaskStore.HistoryRecord(messageId, "MESSAGE_ACCEPTED", now));
    }
}
