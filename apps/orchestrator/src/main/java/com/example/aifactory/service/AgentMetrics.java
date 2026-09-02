package com.example.aifactory.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Low-cardinality operational metrics for agent work and validated delegation topology. */
@Component
public final class AgentMetrics {
    private final MeterRegistry registry;
    private final Set<String> allowedRoles;

    public AgentMetrics(MeterRegistry registry) {
        this(registry, new AgentCatalog());
    }

    @Autowired
    public AgentMetrics(MeterRegistry registry, AgentCatalog catalog) {
        this.registry = registry;
        this.allowedRoles = Set.copyOf(catalog.roles().keySet());
    }

    static AgentMetrics noop() {
        return new AgentMetrics(new SimpleMeterRegistry());
    }

    public void recordUsage(String role, AgentToolLoop.UsageDelta delta) {
        role = safeRole(role);
        increment("ai_agent_tokens", role, "direction", "input", delta.inputTokens());
        increment("ai_agent_tokens", role, "direction", "output", delta.outputTokens());
        increment("ai_agent_cost_micros", role, null, null, delta.costMicros());
        increment("ai_agent_turns", role, null, null, delta.turns());
    }

    public void recordDuration(String role, String outcome, long durationNanos) {
        role = safeRole(role);
        Timer.builder("ai_agent_duration").tag("role", role).tag("outcome", outcome)
                .register(registry).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordPlan(Map<String, JsonNode> nodes) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        Map<String, Integer> children = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            JsonNode node = entry.getValue();
            int depth = depth(entry.getKey(), nodes, depths);
            DistributionSummary.builder("ai_delegation_depth").tag("role", safeRole(node.path("role").asText()))
                    .register(registry).record(depth);
            JsonNode parent = node.path("parent_node_id");
            children.merge(parent.isNull() ? "$supervisor" : parent.asText(), 1, Integer::sum);
        }
        children.forEach((parent, count) -> {
            String role = safeRole("$supervisor".equals(parent)
                    ? "supervisor" : nodes.get(parent).path("role").asText());
            DistributionSummary.builder("ai_delegation_fan_out").tag("role", role)
                    .register(registry).record(count);
        });
    }

    private int depth(String id, Map<String, JsonNode> nodes, Map<String, Integer> known) {
        Integer existing = known.get(id);
        if (existing != null) return existing;
        JsonNode parent = nodes.get(id).path("parent_node_id");
        int value = parent.isNull() ? 1 : 1 + depth(parent.asText(), nodes, known);
        known.put(id, value);
        return value;
    }

    private void increment(String name, String role, String dimension, String value, long amount) {
        Counter.Builder builder = Counter.builder(name).tag("role", role);
        if (dimension != null) builder.tag(dimension, value);
        builder.register(registry).increment(amount);
    }

    private String safeRole(String role) {
        return allowedRoles.contains(role) ? role : "unknown";
    }
}
