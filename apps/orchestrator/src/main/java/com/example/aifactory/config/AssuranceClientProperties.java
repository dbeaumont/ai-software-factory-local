package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-factory.mcp.assurance")
public record AssuranceClientProperties(boolean enabled, String serverName) {
    public AssuranceClientProperties {
        if (enabled && (serverName == null || serverName.isBlank())) {
            throw new IllegalArgumentException("assurance MCP server name is required");
        }
    }
}
