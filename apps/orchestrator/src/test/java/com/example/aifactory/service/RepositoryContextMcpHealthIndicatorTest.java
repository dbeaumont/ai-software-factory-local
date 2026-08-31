package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryContextMcpHealthIndicatorTest {
    @Test
    void reportsDownWhenTheActiveServerIsUnavailable() {
        McpFactoryProperties properties = properties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE);

        assertEquals(Status.DOWN, indicator(properties, false).health().getStatus());
    }

    @Test
    void reportsDegradedWithoutFailingReadinessInShadowMode() {
        McpFactoryProperties properties = properties(true, McpFactoryProperties.ContextMode.MCP_SHADOW);

        var health = indicator(properties, false).health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals("DEGRADED", health.getDetails().get("state"));
    }

    @Test
    void reportsUpWhenDisabledOrAvailable() {
        assertEquals(Status.UP, indicator(properties(false, McpFactoryProperties.ContextMode.DIRECT), false).health().getStatus());
        assertEquals(Status.UP, indicator(properties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE), true).health().getStatus());
    }

    private static RepositoryContextMcpHealthIndicator indicator(McpFactoryProperties properties, boolean available) {
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Availability availability(String serverName) {
                return new Availability(available, available ? null : "internal connection details");
            }
        };
        McpRepositoryContextService service = new McpRepositoryContextService(invoker, properties, new SimpleMeterRegistry());
        return new RepositoryContextMcpHealthIndicator(properties, service);
    }

    private static McpFactoryProperties properties(boolean enabled, McpFactoryProperties.ContextMode mode) {
        return new McpFactoryProperties(enabled, mode, "repository-context-mcp");
    }
}
