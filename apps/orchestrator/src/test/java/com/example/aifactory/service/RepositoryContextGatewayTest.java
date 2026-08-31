package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryContextGatewayTest {
    private final Path repository = Path.of("repository");

    @Test
    void directModeDoesNotCallMcp() throws Exception {
        AtomicInteger mcpCalls = new AtomicInteger();
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct("direct"),
                (path, task, commit) -> {
                    mcpCalls.incrementAndGet();
                    return "mcp";
                }, properties(true, McpFactoryProperties.ContextMode.DIRECT));

        assertEquals("direct", gateway.collect(repository, "task", "commit"));
        assertEquals(0, mcpCalls.get());
    }

    @Test
    void shadowModeFailsOpenToTheDirectContext() throws Exception {
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct("direct"),
                (path, task, commit) -> {
                    throw new IllegalStateException("unavailable");
                }, properties(true, McpFactoryProperties.ContextMode.MCP_SHADOW));

        assertEquals("direct", gateway.collect(repository, "task", "commit"));
    }

    @Test
    void activeModeUsesMcp() throws Exception {
        AtomicInteger directCalls = new AtomicInteger();
        RepositoryContextGateway gateway = new RepositoryContextGateway(new RepositoryContextService() {
                    @Override
                    public String collect(Path repository, String taskId, String sourceCommit) {
                        directCalls.incrementAndGet();
                        return "direct";
                    }
                },
                (path, task, commit) -> "mcp",
                properties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE));

        assertEquals("mcp", gateway.collect(repository, "task", "commit"));
        assertEquals(0, directCalls.get());
    }

    @Test
    void activeModeFailsClosedWhenMcpFails() {
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct("direct"),
                (path, task, commit) -> {
                    throw new IllegalStateException("unavailable");
                }, properties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE));

        assertThrows(IllegalStateException.class, () -> gateway.collect(repository, "task", "commit"));
    }

    @Test
    void activeModeRejectsAnInconsistentDisabledClientConfiguration() {
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct("direct"),
                (path, task, commit) -> "mcp",
                properties(false, McpFactoryProperties.ContextMode.MCP_ACTIVE));

        assertThrows(IllegalStateException.class, () -> gateway.collect(repository, "task", "commit"));
    }

    private static RepositoryContextService direct(String value) {
        return new RepositoryContextService() {
            @Override
            public String collect(Path repository, String taskId, String sourceCommit) {
                return value;
            }
        };
    }

    private static McpFactoryProperties properties(boolean enabled, McpFactoryProperties.ContextMode mode) {
        return new McpFactoryProperties(enabled, mode, "repository-context-mcp");
    }
}
