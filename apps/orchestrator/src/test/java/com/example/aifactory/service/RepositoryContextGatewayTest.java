package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
                }, properties(true, McpFactoryProperties.ContextMode.DIRECT), new SimpleMeterRegistry());

        assertEquals("direct", gateway.collect(repository, "task", "commit"));
        assertEquals(0, mcpCalls.get());
    }

    @Test
    void shadowModeFailsOpenToTheDirectContext() throws Exception {
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct("direct"),
                (path, task, commit) -> {
                    throw new IllegalStateException("unavailable");
                }, properties(true, McpFactoryProperties.ContextMode.MCP_SHADOW), new SimpleMeterRegistry());

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
                properties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE), new SimpleMeterRegistry());

        assertEquals("mcp", gateway.collect(repository, "task", "commit"));
        assertEquals(0, directCalls.get());
    }

    @Test
    void activeModeRollsOutPerRole() throws Exception {
        AtomicInteger directCalls = new AtomicInteger();
        AtomicInteger mcpCalls = new AtomicInteger();
        RepositoryContextGateway gateway = new RepositoryContextGateway(new RepositoryContextService() {
                    @Override
                    public String collect(Path repository, String taskId, String sourceCommit) {
                        directCalls.incrementAndGet();
                        return "direct";
                    }
                }, (path, task, commit) -> {
                    mcpCalls.incrementAndGet();
                    return "mcp";
                }, properties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE, Set.of("planner")),
                new SimpleMeterRegistry());

        assertEquals("mcp", gateway.collectForRole(repository, "task", "commit", "planner"));
        assertEquals("direct", gateway.collectForRole(repository, "task", "commit", "developer"));
        assertEquals(1, mcpCalls.get());
        assertEquals(1, directCalls.get());
    }

    @Test
    void activeModeFailsClosedWhenMcpFails() {
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct("direct"),
                (path, task, commit) -> {
                    throw new IllegalStateException("unavailable");
                }, properties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE), new SimpleMeterRegistry());

        assertThrows(IllegalStateException.class, () -> gateway.collect(repository, "task", "commit"));
    }

    @Test
    void activeModeRejectsAnInconsistentDisabledClientConfiguration() {
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct("direct"),
                (path, task, commit) -> "mcp",
                properties(false, McpFactoryProperties.ContextMode.MCP_ACTIVE), new SimpleMeterRegistry());

        assertThrows(IllegalStateException.class, () -> gateway.collect(repository, "task", "commit"));
    }

    @Test
    void shadowModeRecordsVolumeCoverageAndCitationMetrics() throws Exception {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        String direct = "\n--- FILE: pom.xml ---\ndirect\n\n--- FILE: src/A.java ---\nclass A {}\n";
        String mcp = "\n--- FILE: pom.xml ---\nSOURCE: repo://task/" + "a".repeat(40)
                + "/pom.xml#sha256=" + "b".repeat(64) + "\nmcp\n";
        RepositoryContextGateway gateway = new RepositoryContextGateway(direct(direct),
                (path, task, commit) -> mcp,
                properties(true, McpFactoryProperties.ContextMode.MCP_SHADOW), metrics);

        assertEquals(direct, gateway.collect(repository, "task", "a".repeat(40)));
        assertEquals(1.0, metrics.counter("ai_factory_mcp_context_shadow_runs", "outcome", "success").count());
        assertEquals(0.5, metrics.get("ai_factory_mcp_context_shadow_file_coverage_ratio").summary().mean());
        assertEquals(1.0, metrics.get("ai_factory_mcp_context_shadow_citation_validity_ratio").summary().mean());
        assertEquals(mcp.length(), metrics.get("ai_factory_mcp_context_shadow_chars")
                .tag("source", "mcp").summary().mean());
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

    private static McpFactoryProperties properties(boolean enabled, McpFactoryProperties.ContextMode mode,
                                                   Set<String> roles) {
        return new McpFactoryProperties(enabled, mode, "repository-context-mcp", false,
                McpFactoryProperties.SandboxMode.DIRECT, "sandbox-execution-mcp",
                Duration.ofMillis(250), Duration.ofMinutes(20), roles);
    }
}
