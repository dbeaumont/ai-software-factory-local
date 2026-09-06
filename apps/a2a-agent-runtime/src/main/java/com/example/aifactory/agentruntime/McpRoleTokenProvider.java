package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.RoleScopedAgentContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Reads the externally rotated, role-specific MCP bearer token only when a connection is opened. */
final class McpRoleTokenProvider {
    private final RoleScopedAgentContext role;
    private final AgentMcpProperties properties;

    McpRoleTokenProvider(RoleScopedAgentContext role, AgentMcpProperties properties) {
        this.role = role;
        this.properties = properties;
    }

    char[] acquire() {
        if (!properties.securityEnabled()) return new char[0];
        String expectedClient = "ai-factory-agent-" + role.identity().role();
        if (!expectedClient.equals(properties.clientId())) {
            throw new SecurityException("MCP client identity is not bound to the active agent role");
        }
        try {
            byte[] bytes = Files.readAllBytes(properties.accessTokenFile());
            try {
                char[] token = new String(bytes, StandardCharsets.UTF_8).trim().toCharArray();
                if (token.length < 16) throw new SecurityException("MCP role token is unavailable");
                return token;
            } finally {
                java.util.Arrays.fill(bytes, (byte) 0);
            }
        } catch (SecurityException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SecurityException("MCP role token is unavailable", failure);
        }
    }
}
