package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai-factory.mcp")
public record McpFactoryProperties(
        boolean enabled,
        ContextMode repositoryContextMode,
        String repositoryContextServerName,
        boolean sandboxEnabled,
        SandboxMode sandboxMode,
        String sandboxServerName,
        Duration sandboxPollInterval,
        Duration sandboxPollTimeout) {

    public McpFactoryProperties(boolean enabled, ContextMode repositoryContextMode, String repositoryContextServerName) {
        this(enabled, repositoryContextMode, repositoryContextServerName, false, SandboxMode.DIRECT,
                "sandbox-execution-mcp", Duration.ofMillis(250), Duration.ofMinutes(20));
    }

    public enum ContextMode {
        DIRECT,
        MCP_SHADOW,
        MCP_ACTIVE
    }

    public enum SandboxMode {
        DIRECT,
        MCP_SHADOW,
        MCP_ACTIVE
    }
}
