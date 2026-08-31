package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("sandboxExecutionMcp")
public class SandboxMcpHealthIndicator implements HealthIndicator {
    private final McpFactoryProperties properties;
    private final McpServerRegistry registry;

    public SandboxMcpHealthIndicator(McpFactoryProperties properties, McpServerRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    public Health health() {
        if (!properties.sandboxEnabled()) {
            return Health.up().withDetail("enabled", false).withDetail("mode", properties.sandboxMode()).build();
        }
        McpServerRegistry.ServerStatus status = registry.status("sandbox-execution");
        if (status.state() == McpServerRegistry.HealthState.READY) {
            return Health.up()
                    .withDetail("enabled", true)
                    .withDetail("mode", properties.sandboxMode())
                    .withDetail("state", status.state().name())
                    .withDetail("protocolVersion", status.protocolVersion())
                    .withDetail("serverVersion", status.serverVersion())
                    .build();
        }
        Health.Builder health = properties.sandboxMode() == McpFactoryProperties.SandboxMode.MCP_ACTIVE
                ? Health.down()
                : Health.up();
        return health.withDetail("enabled", true)
                .withDetail("mode", properties.sandboxMode())
                .withDetail("state", status.state().name())
                .withDetail("error", status.message())
                .build();
    }
}
