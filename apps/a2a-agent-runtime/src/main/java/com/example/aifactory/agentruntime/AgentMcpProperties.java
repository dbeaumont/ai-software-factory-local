package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("ai-factory.agent-runtime.mcp")
public record AgentMcpProperties(boolean enabled, Duration requestTimeout,
                                 URI repositoryContextUrl, URI evidenceUrl) {
    public AgentMcpProperties {
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("MCP request timeout must be positive");
        }
        requireHttp("repository context", repositoryContextUrl);
        requireHttp("evidence", evidenceUrl);
    }

    private static void requireHttp(String name, URI uri) {
        if (uri == null || uri.getHost() == null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(name + " MCP URL must be an HTTP(S) authority without credentials");
        }
    }
}
