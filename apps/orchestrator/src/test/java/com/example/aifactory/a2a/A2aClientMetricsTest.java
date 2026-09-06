package com.example.aifactory.a2a;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aClientMetricsTest {
    @Test
    void recordsEveryRequiredClientSignalWithBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        A2aClientMetrics metrics = new A2aClientMetrics(registry);
        assertThat(metrics.call("developer", "developer.code-task-v1", "send", () -> "ok")).isEqualTo("ok");
        metrics.payload("developer", "developer.code-task-v1", 512);
        metrics.retry("developer", "developer.code-task-v1");
        metrics.reconciliation("developer", "remote_task");
        metrics.cardValidation("developer", "accepted");
        metrics.notificationAge("developer", Instant.parse("2026-09-06T12:00:00Z"),
                Instant.parse("2026-09-06T12:00:02Z"));

        assertThat(registry.getMeters()).extracting(meter -> meter.getId().getName())
                .contains("ai.factory.a2a.client.duration", "ai.factory.a2a.client.payload.bytes",
                        "ai.factory.a2a.client.retries", "ai.factory.a2a.client.reconciliations",
                        "ai.factory.a2a.client.card.validations", "ai.factory.a2a.client.notification.age");
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .noneMatch(key -> key.endsWith(".id") || key.contains("tenant")));
    }

    @Test
    void classifiesTimeoutsSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        A2aClientMetrics metrics = new A2aClientMetrics(registry);
        assertThatThrownBy(() -> metrics.call("developer", "none", "get",
                () -> { throw new IllegalStateException(new TimeoutException("late")); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.get("ai.factory.a2a.client.timeouts").counter().count()).isEqualTo(1);
        assertThat(registry.get("ai.factory.a2a.client.duration").tag("result", "timeout").timer().count())
                .isEqualTo(1);
    }
}
