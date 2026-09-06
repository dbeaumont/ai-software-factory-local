package com.example.aifactory.evidence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties("ai-factory.mcp.role-security")
public record McpRoleSecurityProperties(boolean enabled, URI issuer, String audience, String serverName) {
    public McpRoleSecurityProperties {
        if (enabled && (issuer == null || !"https".equalsIgnoreCase(issuer.getScheme())
                || audience == null || audience.isBlank() || serverName == null || serverName.isBlank())) {
            throw new IllegalArgumentException("Enabled MCP role security configuration is incomplete");
        }
    }
}
