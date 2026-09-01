package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-factory.mcp.scm-delivery")
public record ScmDeliveryClientProperties(
        boolean enabled,
        String serverName,
        String approver,
        String approvalKey) {

    public ScmDeliveryClientProperties {
        if (enabled && (serverName == null || serverName.isBlank())) {
            throw new IllegalArgumentException("SCM MCP server name is required");
        }
        if (enabled && (approver == null || approver.isBlank())) {
            throw new IllegalArgumentException("SCM approver is required");
        }
        if (enabled && (approvalKey == null || approvalKey.length() < 32)) {
            throw new IllegalArgumentException("SCM approval attestation key must contain at least 32 characters");
        }
    }
}
