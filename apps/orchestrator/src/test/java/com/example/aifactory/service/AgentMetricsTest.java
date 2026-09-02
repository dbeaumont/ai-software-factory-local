package com.example.aifactory.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AgentMetrics metrics = new AgentMetrics(registry);

    @Test
    void recordsUsageAndDurationByBoundedRole() {
        metrics.recordUsage("developer", new AgentToolLoop.UsageDelta(11, 7, 123, 2, 0));
        metrics.recordDuration("developer", "success", TimeUnit.MILLISECONDS.toNanos(25));

        assertThat(registry.get("ai_agent_tokens").tag("role", "developer").tag("direction", "input")
                .counter().count()).isEqualTo(11);
        assertThat(registry.get("ai_agent_tokens").tag("role", "developer").tag("direction", "output")
                .counter().count()).isEqualTo(7);
        assertThat(registry.get("ai_agent_cost_micros").tag("role", "developer").counter().count())
                .isEqualTo(123);
        assertThat(registry.get("ai_agent_turns").tag("role", "developer").counter().count()).isEqualTo(2);
        assertThat(registry.get("ai_agent_duration").tag("role", "developer").tag("outcome", "success")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(25);
    }

    @Test
    void recordsValidatedDepthAndFanOutByRole() throws Exception {
        JsonNode plan = new ObjectMapper().readTree("""
                [{"node_id":"code","role":"code-agent","parent_node_id":null},
                 {"node_id":"dev","role":"developer","parent_node_id":"code"}]
                """);
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        plan.forEach(node -> nodes.put(node.path("node_id").asText(), node));

        metrics.recordPlan(nodes);

        assertThat(registry.get("ai_delegation_depth").tag("role", "developer").summary().max()).isEqualTo(2);
        assertThat(registry.get("ai_delegation_fan_out").tag("role", "supervisor").summary().max()).isEqualTo(1);
        assertThat(registry.get("ai_delegation_fan_out").tag("role", "code-agent").summary().max()).isEqualTo(1);
    }

    @Test
    void foldsUnknownRolesIntoOneBoundedTag() {
        metrics.recordUsage("task-controlled-role", new AgentToolLoop.UsageDelta(1, 0, 0, 0, 0));

        assertThat(registry.get("ai_agent_tokens").tag("role", "unknown").tag("direction", "input")
                .counter().count()).isEqualTo(1);
        assertThat(registry.find("ai_agent_tokens").tag("role", "task-controlled-role").counter()).isNull();
    }
}
