package com.example.aifactory.service;

import com.example.aifactory.config.McpClientProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpServerRegistry {
    private final McpClientProperties properties;
    private final McpToolInvoker invoker;
    private final Map<String, ServerStatus> statuses = new ConcurrentHashMap<>();

    public McpServerRegistry(McpClientProperties properties, McpToolInvoker invoker) {
        this.properties = properties;
        this.invoker = invoker;
        properties.servers().forEach((key, server) -> statuses.put(key, disabled(key, server)));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void negotiateAtStartup() {
        refreshAll();
    }

    public Map<String, ServerStatus> refreshAll() {
        properties.servers().keySet().forEach(this::refresh);
        return statuses();
    }

    public ServerStatus refresh(String key) {
        McpClientProperties.Server expected = server(key);
        ServerStatus status;
        if (!properties.enabled() || !expected.enabled()) {
            status = disabled(key, expected);
        } else {
            status = evaluate(key, expected, invoker.describe(expected.expectedName()));
        }
        statuses.put(key, status);
        return status;
    }

    public ServerStatus status(String key) {
        server(key);
        return statuses.get(key);
    }

    public Map<String, ServerStatus> statuses() {
        return Map.copyOf(new LinkedHashMap<>(statuses));
    }

    private ServerStatus evaluate(
            String key,
            McpClientProperties.Server expected,
            McpToolInvoker.ServerDescriptor actual) {
        if (!actual.available()) {
            return new ServerStatus(key, expected.expectedName(), HealthState.DEGRADED,
                    actual.protocolVersion(), actual.version(), actual.tools(),
                    safe(actual.error(), "server unavailable"));
        }
        if (!properties.acceptedProtocolVersions().contains(actual.protocolVersion())) {
            return incompatible(key, expected, actual, "unsupported MCP protocol version");
        }
        if (!expected.expectedName().equals(actual.name())) {
            return incompatible(key, expected, actual, "unexpected server identity");
        }
        if (!expected.expectedVersion().equals(actual.version())) {
            return incompatible(key, expected, actual, "unexpected server version");
        }
        if (!expected.allowedTools().equals(actual.tools())) {
            return incompatible(key, expected, actual, "tool catalogue differs from the allowlist");
        }
        return new ServerStatus(key, expected.expectedName(), HealthState.READY,
                actual.protocolVersion(), actual.version(), actual.tools(), "compatible");
    }

    private ServerStatus incompatible(
            String key,
            McpClientProperties.Server expected,
            McpToolInvoker.ServerDescriptor actual,
            String message) {
        return new ServerStatus(key, expected.expectedName(), HealthState.INCOMPATIBLE,
                actual.protocolVersion(), actual.version(), actual.tools(), message);
    }

    private ServerStatus disabled(String key, McpClientProperties.Server server) {
        return new ServerStatus(key, server.expectedName(), HealthState.DEGRADED,
                null, null, Set.of(), "disabled by configuration");
    }

    private McpClientProperties.Server server(String key) {
        McpClientProperties.Server server = properties.servers().get(key);
        if (server == null) {
            throw new IllegalArgumentException("unknown MCP server key: " + key);
        }
        return server;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public enum HealthState {
        READY,
        DEGRADED,
        INCOMPATIBLE
    }

    public record ServerStatus(
            String key,
            String expectedName,
            HealthState state,
            String protocolVersion,
            String serverVersion,
            Set<String> tools,
            String message) {
        public ServerStatus {
            tools = tools == null ? Set.of() : Set.copyOf(tools);
        }
    }
}
