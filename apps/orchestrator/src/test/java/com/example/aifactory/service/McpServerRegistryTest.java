package com.example.aifactory.service;

import com.example.aifactory.config.McpClientProperties;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerRegistryTest {

    @Test
    void reportsReadyOnlyForTheExactPinnedContract() {
        McpServerRegistry registry = registry(descriptor(
                "2025-06-18", "repository-context-mcp", "0.1.0",
                Set.of("context.list_tree", "context.read_file")));

        McpServerRegistry.ServerStatus status = registry.refresh("repository-context");

        assertThat(status.state()).isEqualTo(McpServerRegistry.HealthState.READY);
        assertThat(status.message()).isEqualTo("compatible");
    }

    @Test
    void reportsDegradedWhenTheServerCannotBeInspected() {
        McpServerRegistry registry = registry(new McpToolInvoker.ServerDescriptor(
                false, null, null, null, Set.of(), "connection refused"));

        McpServerRegistry.ServerStatus status = registry.refresh("repository-context");

        assertThat(status.state()).isEqualTo(McpServerRegistry.HealthState.DEGRADED);
        assertThat(status.message()).isEqualTo("connection refused");
    }

    @Test
    void reportsIncompatibleForProtocolIdentityVersionMissingOrUnexpectedTools() {
        assertIncompatible(descriptor("2024-11-05", "repository-context-mcp", "0.1.0",
                Set.of("context.list_tree", "context.read_file")));
        assertIncompatible(descriptor("2025-06-18", "other-mcp", "0.1.0",
                Set.of("context.list_tree", "context.read_file")));
        assertIncompatible(descriptor("2025-06-18", "repository-context-mcp", "0.2.0",
                Set.of("context.list_tree", "context.read_file")));
        assertIncompatible(descriptor("2025-06-18", "repository-context-mcp", "0.1.0",
                Set.of("context.list_tree")));
        assertIncompatible(descriptor("2025-06-18", "repository-context-mcp", "0.1.0",
                Set.of("context.list_tree", "context.read_file", "context.unapproved")));
    }

    private static void assertIncompatible(McpToolInvoker.ServerDescriptor descriptor) {
        assertThat(registry(descriptor).refresh("repository-context").state())
                .isEqualTo(McpServerRegistry.HealthState.INCOMPATIBLE);
    }

    private static McpServerRegistry registry(McpToolInvoker.ServerDescriptor descriptor) {
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Availability availability(String serverName) {
                return new Availability(descriptor.available(), descriptor.error());
            }

            @Override
            public ServerDescriptor describe(String serverName) {
                return descriptor;
            }
        };
        return new McpServerRegistry(properties(), invoker);
    }

    private static McpToolInvoker.ServerDescriptor descriptor(
            String protocolVersion, String name, String version, Set<String> tools) {
        return new McpToolInvoker.ServerDescriptor(true, protocolVersion, name, version, tools, null);
    }

    private static McpClientProperties properties() {
        McpClientProperties.RetryPolicy readOnly = new McpClientProperties.RetryPolicy(
                3, Duration.ofMillis(200), Duration.ofSeconds(2), 2.0, 0.2);
        McpClientProperties.RetryPolicy effectful = new McpClientProperties.RetryPolicy(
                2, Duration.ofMillis(500), Duration.ofSeconds(2), 2.0, 0.2);
        McpClientProperties.Server server = new McpClientProperties.Server(
                true,
                URI.create("http://repository-context-mcp:8091"),
                "repository-context-mcp",
                Duration.ofSeconds(20),
                "repository-context-mcp",
                "0.1.0",
                Set.of("context.list_tree", "context.read_file"));
        return new McpClientProperties(
                true,
                Duration.ofSeconds(20),
                65_536,
                16,
                4,
                Set.of("2025-06-18"),
                new McpClientProperties.Retry(readOnly, effectful),
                Map.of("repository-context", server));
    }
}
