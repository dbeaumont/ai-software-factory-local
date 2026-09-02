package com.example.aifactory.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/** Counter source for rate-based workflow reliability indicators. */
@Component
public final class WorkflowOperationalMetrics {
    private static final Set<String> EVENTS = Set.of(
            "retry", "repair", "replan", "contradiction", "escalation");
    private static final Set<String> SOURCES = Set.of(
            "agent", "mcp", "patch", "supervisor", "consolidation", "human");
    private final MeterRegistry registry;

    public WorkflowOperationalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static WorkflowOperationalMetrics noop() {
        return new WorkflowOperationalMetrics(new SimpleMeterRegistry());
    }

    public void retry(String source) { increment("retry", source, 1); }
    public void repair() { increment("repair", "patch", 1); }
    public void replan() { increment("replan", "supervisor", 1); }
    public void contradictions(long count) { increment("contradiction", "consolidation", count); }
    public void escalation() { increment("escalation", "human", 1); }

    private void increment(String event, String source, long amount) {
        String safeEvent = normalize(event, EVENTS);
        String safeSource = normalize(source, SOURCES);
        Counter.builder("ai_workflow_events").tag("event", safeEvent).tag("source", safeSource)
                .register(registry).increment(Math.max(0, amount));
    }

    private static String normalize(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "unknown";
    }
}
