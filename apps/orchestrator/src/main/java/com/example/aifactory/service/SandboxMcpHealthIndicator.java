package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("sandboxExecutionMcp")
public class SandboxMcpHealthIndicator implements HealthIndicator {
    private final McpFactoryProperties properties;
    private final McpSandboxService service;

    public SandboxMcpHealthIndicator(McpFactoryProperties properties, McpSandboxService service) {
        this.properties = properties;
        this.service = service;
    }

    @Override
    public Health health() {
        if (!properties.sandboxEnabled()) {
            return Health.up().withDetail("enabled", false).withDetail("mode", properties.sandboxMode()).build();
        }
        McpSandboxService.Availability availability = service.availability();
        if (availability.available()) {
            return Health.up().withDetail("enabled", true).withDetail("mode", properties.sandboxMode()).build();
        }
        Health.Builder health = properties.sandboxMode() == McpFactoryProperties.SandboxMode.MCP_ACTIVE
                ? Health.down()
                : Health.up().withDetail("state", "DEGRADED");
        return health.withDetail("enabled", true)
                .withDetail("mode", properties.sandboxMode())
                .withDetail("error", "sandbox-execution-mcp unavailable")
                .build();
    }
}
