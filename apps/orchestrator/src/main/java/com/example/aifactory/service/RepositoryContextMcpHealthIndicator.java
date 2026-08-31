package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("repositoryContextMcp")
public class RepositoryContextMcpHealthIndicator implements HealthIndicator {
    private final McpFactoryProperties properties;
    private final McpRepositoryContextService contextService;

    public RepositoryContextMcpHealthIndicator(McpFactoryProperties properties,
                                               McpRepositoryContextService contextService) {
        this.properties = properties;
        this.contextService = contextService;
    }

    @Override
    public Health health() {
        if (!properties.enabled()) {
            return Health.up()
                    .withDetail("enabled", false)
                    .withDetail("mode", properties.repositoryContextMode())
                    .build();
        }
        McpRepositoryContextService.Availability availability = contextService.availability();
        if (availability.available()) {
            return Health.up()
                    .withDetail("enabled", true)
                    .withDetail("mode", properties.repositoryContextMode())
                    .build();
        }
        Health.Builder health = properties.repositoryContextMode() == McpFactoryProperties.ContextMode.MCP_ACTIVE
                ? Health.down()
                : Health.up().withDetail("state", "DEGRADED");
        return health
                .withDetail("enabled", true)
                .withDetail("mode", properties.repositoryContextMode())
                .withDetail("error", "repository-context-mcp unavailable")
                .build();
    }
}
