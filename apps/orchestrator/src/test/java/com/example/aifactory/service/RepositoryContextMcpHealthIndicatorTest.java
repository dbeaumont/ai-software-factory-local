package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.example.aifactory.config.McpClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

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

            @Override
            public ServerDescriptor describe(String serverName) {
                return available
                        ? new ServerDescriptor(true, "2025-06-18", "repository-context-mcp", "0.1.0",
                        Set.of("context.list_tree"), null)
                        : new ServerDescriptor(false, null, null, null, Set.of(), "internal connection details");
            }
        };
        McpServerRegistry registry = new McpServerRegistry(clientProperties(), invoker);
        registry.refresh("repository-context");
        return new RepositoryContextMcpHealthIndicator(properties, registry);
    }

    private static McpFactoryProperties properties(boolean enabled, McpFactoryProperties.ContextMode mode) {
        return new McpFactoryProperties(enabled, mode, "repository-context-mcp");
    }

    private static McpClientProperties clientProperties() {
        McpClientProperties.RetryPolicy readOnly = new McpClientProperties.RetryPolicy(
                3, Duration.ofMillis(200), Duration.ofSeconds(2), 2.0, 0.2);
        McpClientProperties.RetryPolicy effectful = new McpClientProperties.RetryPolicy(
                2, Duration.ofMillis(500), Duration.ofSeconds(2), 2.0, 0.2);
        McpClientProperties.Server server = new McpClientProperties.Server(
                true, URI.create("http://repository-context-mcp:8091"), "repository-context-mcp",
                Duration.ofSeconds(20), "repository-context-mcp", "0.1.0", Set.of("context.list_tree"));
        return new McpClientProperties(true, Duration.ofSeconds(20), 65_536, 16, 4,
                Set.of("2025-06-18"), new McpClientProperties.Retry(readOnly, effectful),
                Map.of("repository-context", server));
    }
}
