package com.example.aifactory.agentruntime;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class A2aServerMetricsTest {
    @Test
    void recordsTheCompleteServerSurfaceWithoutIdentifierTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        A2aServerMetrics metrics = new A2aServerMetrics(registry,
                new AgentRuntimeProperties("developer", URI.create("https://developer.internal/a2a")), store);
        Instant submittedAt = Instant.parse("2026-09-06T12:00:00Z");
        A2aTaskStore.StoredTask task = new A2aTaskStore.StoredTask(
                "task-sensitive", "context-sensitive", "message-sensitive", "digest",
                "developer", "developer.code-task-v1", "caller", "tenant", "delegation",
                submittedAt, A2aSendMessageService.TaskState.SUBMITTED, 0, "{}", null, null);
        store.createOrGet(task, new A2aTaskStore.HistoryRecord(
                task.messageId(), "MESSAGE_ACCEPTED", submittedAt));

        metrics.admission(task.skill(), true);
        metrics.admission(task.skill(), false);
        metrics.authenticationRefusal();
        metrics.deduplication(task.skill(), "send");
        metrics.idempotencyCollision(task.skill(), "send");
        metrics.readiness(true);
        metrics.polling(task.skill(), "get", task.state());
        metrics.notification("retries", A2aSendMessageService.TaskState.WORKING);
        metrics.notification("delivered", A2aSendMessageService.TaskState.COMPLETED);
        metrics.notification("failed", A2aSendMessageService.TaskState.FAILED);
        metrics.transition(task, A2aSendMessageService.TaskState.WORKING, submittedAt.plusSeconds(2));
        metrics.transition(task, A2aSendMessageService.TaskState.COMPLETED, submittedAt.plusSeconds(3));

        assertThat(registry.find("ai.factory.a2a.server.active.tasks").gauge().value()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.backlog").gauge().value()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.ready").gauge().value()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.oldest.active.age").gauge().value()).isGreaterThan(0);
        assertThat(registry.find("ai.factory.a2a.server.admissions.accepted").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.admissions.rejected").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.auth.refusals").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.deduplications").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.idempotency.collisions").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.polling").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai.factory.a2a.server.transitions").counters())
                .extracting(io.micrometer.core.instrument.Counter::count).containsOnly(1.0, 1.0);
        assertThat(registry.find("ai.factory.a2a.server.pickup.duration").timer().totalTime(
                java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(registry.find("ai.factory.a2a.server.task.duration").timer().totalTime(
                java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(3);
        assertThat(registry.find("ai.factory.a2a.server.duplicate.executions").counter().count()).isZero();
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .noneMatch(key -> key.contains("task") && key.contains("id"))
                .noneMatch(key -> key.contains("message") || key.contains("workflow"));
    }
}
