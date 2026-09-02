package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpRequestMetadataTest {
    @Test
    void createsAConsistentBoundedW3cEnvelope() {
        Instant before = Instant.now();

        Map<String, Object> arguments = McpRequestMetadata.create(
                "task-1", "a".repeat(40), "workflow", Duration.ofMinutes(5)).arguments();

        String traceId = (String) arguments.get("trace_id");
        String traceparent = (String) arguments.get("traceparent");
        assertThat(arguments.get("task_id")).isEqualTo("task-1");
        assertThat(arguments.get("attempt_id").toString()).matches("[0-9a-f]{32}");
        assertThat(traceId).matches("[0-9a-f]{32}");
        assertThat(traceparent).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(traceparent).contains("-" + traceId + "-");
        assertThat(arguments).containsKeys("run_id", "delegation_id", "agent_run_id");
        assertThat(Instant.parse(arguments.get("deadline").toString()))
                .isAfter(before.plus(Duration.ofMinutes(4)));
    }
}
