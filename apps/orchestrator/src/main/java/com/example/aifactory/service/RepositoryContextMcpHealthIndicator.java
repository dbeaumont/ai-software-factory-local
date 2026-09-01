package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("repositoryContextMcp")
public class RepositoryContextMcpHealthIndicator implements HealthIndicator {
    private final McpFactoryProperties properties;
    private final McpServerRegistry registry;

    public RepositoryContextMcpHealthIndicator(McpFactoryProperties properties,
                                               McpServerRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("mode", properties.repositoryContextMode())
                    .build();
        }
        McpServerRegistry.ServerStatus status = registry.status("repository-context");
        if (status.state() == McpServerRegistry.HealthState.READY) {
            return Health.up()
                    .withDetail("enabled", true)
                    .withDetail("mode", properties.repositoryContextMode())
                    .withDetail("state", status.state().name())
                    .withDetail("protocolVersion", status.protocolVersion())
                    .withDetail("serverVersion", status.serverVersion())
                    .build();
        }
        Health.Builder health = properties.repositoryContextMode() == McpFactoryProperties.ContextMode.MCP_ACTIVE
                ? Health.down()
                : Health.up();
        return health
                .withDetail("enabled", true)
                .withDetail("mode", properties.repositoryContextMode())
                .withDetail("state", status.state().name())
                .withDetail("error", status.message())
                .build();
    }
}
