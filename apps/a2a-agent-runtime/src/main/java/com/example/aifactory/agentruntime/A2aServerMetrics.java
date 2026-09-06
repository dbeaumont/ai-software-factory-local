package com.example.aifactory.agentruntime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Bounded-cardinality server telemetry exported by Micrometer through OpenTelemetry. */
@Component
final class A2aServerMetrics {
    private final MeterRegistry registry;
    private final String role;

    @Autowired
    A2aServerMetrics(MeterRegistry registry, AgentRuntimeProperties runtime, A2aTaskStore store) {
        this.registry = registry;
        this.role = runtime.role();
        Gauge.builder("ai.factory.a2a.server.active.tasks", store, value -> value.activeCount(role, null))
                .tags(dimensions("none", "execute", "none").tags()).register(registry);
        Gauge.builder("ai.factory.a2a.server.backlog", store, value -> value.backlogCount(role))
                .tags(dimensions("none", "execute", "submitted").tags()).register(registry);
    }

    private A2aServerMetrics() {
        this.registry = null;
        this.role = "developer";
    }

    static A2aServerMetrics disabled() {
        return new A2aServerMetrics();
    }

    void admission(String skill, boolean accepted) {
        increment(accepted ? "ai.factory.a2a.server.admissions.accepted"
                : "ai.factory.a2a.server.admissions.rejected", skill, "send", accepted ? "submitted" : "rejected");
    }

    void authenticationRefusal() {
        increment("ai.factory.a2a.server.auth.refusals", "none", "send", "rejected");
    }

    void deduplication(String skill, String operation) {
        increment("ai.factory.a2a.server.deduplications", skill, operation, "none");
    }

    void transition(A2aTaskStore.StoredTask task, A2aSendMessageService.TaskState state, Instant occurredAt) {
        String stateTag = state.name().toLowerCase(java.util.Locale.ROOT);
        increment("ai.factory.a2a.server.transitions", task.skill(), "execute", stateTag);
        if (state.terminal() && registry != null) {
            Timer.builder("ai.factory.a2a.server.task.duration")
                    .tags(dimensions(task.skill(), "execute", stateTag).tags())
                    .register(registry)
                    .record(nonNegativeDuration(task.submittedAt(), occurredAt));
        }
    }

    void polling(String skill, String operation, A2aSendMessageService.TaskState state) {
        increment("ai.factory.a2a.server.polling", skill, operation,
                state == null ? "none" : state.name().toLowerCase(java.util.Locale.ROOT));
    }

    void notification(String outcome, A2aSendMessageService.TaskState state) {
        if (!java.util.Set.of("delivered", "retries", "failed").contains(outcome)) {
            throw new IllegalArgumentException("Unbounded A2A notification outcome");
        }
        increment("ai.factory.a2a.server.notifications." + outcome, "none", "notify",
                state.name().toLowerCase(java.util.Locale.ROOT));
    }

    private void increment(String name, String skill, String operation, String state) {
        if (registry != null) registry.counter(name, dimensions(skill, operation, state).tags()).increment();
    }

    private A2aMetricDimensions dimensions(String skill, String operation, String state) {
        return new A2aMetricDimensions(role, skill, operation, "1.0", state);
    }

    private static Duration nonNegativeDuration(Instant start, Instant end) {
        Duration duration = Duration.between(start, end);
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
