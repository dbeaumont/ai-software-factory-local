package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoleScopedMcpClientTest {
    @Test
    void configuresOnlyServersAndDefinitionsRequiredByTheActiveRole() {
        RecordingSessions sessions = new RecordingSessions();
        RoleScopedMcpClient developer = client("developer", sessions, true);

        assertEquals(Set.of("repository-context-mcp"), developer.configuredServers());
        assertEquals(Set.of("context.get_dependencies", "context.get_repository_rules", "context.get_symbols",
                        "context.list_tree", "context.read_file", "context.search_code"),
                developer.definitions().stream().map(definition -> definition.name())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, developer.openConnectionCount());
    }

    @Test
    void connectsLazilyOnceAndRechecksRoleGrantOnEveryCall() {
        RecordingSessions sessions = new RecordingSessions();
        RoleScopedMcpClient supervisor = client("supervisor", sessions, true);

        assertEquals(Set.of("repository-context-mcp", "evidence-mcp"), supervisor.configuredServers());
        assertEquals("{\"ok\":true}", supervisor.call("context.list_tree", Map.of()));
        assertEquals("{\"ok\":true}", supervisor.call("context.search_code", Map.of("query", "Agent")));
        assertEquals("supervisor", sessions.lastArguments.get("actor"));
        assertEquals(List.of("repository-context-mcp"), sessions.connected);
        assertEquals(1, supervisor.openConnectionCount());
        assertThrows(SecurityException.class, () -> supervisor.call("evidence.read", Map.of("uri", "evidence://x")));
        assertThrows(SecurityException.class, () -> supervisor.call("scm.create_commit", Map.of()));
        assertThrows(SecurityException.class, () -> supervisor.call(
                "context.list_tree", Map.of("actor", "workflow")));
        assertEquals(List.of("repository-context-mcp"), sessions.connected);
    }

    @Test
    void failsClosedWhenMcpIsDisabled() {
        RoleScopedMcpClient client = client("patch-repair", new RecordingSessions(), false);
        assertThrows(IllegalStateException.class, () -> client.call("context.read_file", Map.of("path", "README.md")));
    }

    @Test
    void injectsTheScopedValidatedTraceContextIntoEveryMcpCall() {
        RecordingSessions sessions = new RecordingSessions();
        RoleScopedMcpClient developer = client("developer", sessions, true);
        A2aW3cTraceContext trace = new A2aW3cTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "task.id=task-1");

        trace.call(() -> developer.call("context.list_tree", Map.of("path", "apps")));

        assertEquals(trace.traceparent(), sessions.lastArguments.get("traceparent"));
        assertEquals(trace.baggage(), sessions.lastArguments.get("baggage"));
        assertEquals("developer", sessions.lastArguments.get("actor"));
    }

    @Test
    void retriesTheSameBoundedMcpCallAfterATransientOutage() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RoleScopedMcpClient client = new RoleScopedMcpClient(
                RoleScopedAgentContext.load("developer", new ObjectMapper()),
                new AgentMcpProperties(true, Duration.ofSeconds(20),
                        URI.create("http://repository-context-mcp:8091"), URI.create("http://evidence-mcp:8095")),
                (name, uri, timeout) -> new RoleScopedMcpClient.Session() {
                    @Override public Set<String> tools() { return Set.of("context.list_tree"); }
                    @Override public String call(String toolName, Map<String, Object> arguments) {
                        if (calls.incrementAndGet() == 1) throw new IllegalStateException("MCP unavailable");
                        return "{\"ok\":true}";
                    }
                    @Override public void close() { }
                });

        assertThrows(IllegalStateException.class, () -> client.call("context.list_tree", Map.of("path", "apps")));
        assertEquals("{\"ok\":true}", client.call("context.list_tree", Map.of("path", "apps")));
        assertEquals(2, calls.get());
        assertEquals(1, client.openConnectionCount());
    }

    private static RoleScopedMcpClient client(String role, RecordingSessions sessions, boolean enabled) {
        return new RoleScopedMcpClient(RoleScopedAgentContext.load(role, new ObjectMapper()),
                new AgentMcpProperties(enabled, Duration.ofSeconds(20),
                        URI.create("http://repository-context-mcp:8091"), URI.create("http://evidence-mcp:8095")),
                sessions);
    }

    private static final class RecordingSessions implements RoleScopedMcpClient.SessionFactory {
        private final List<String> connected = new ArrayList<>();
        private Map<String, Object> lastArguments = Map.of();
        @Override public RoleScopedMcpClient.Session connect(String serverName, URI uri, Duration timeout) {
            connected.add(serverName);
            return new RoleScopedMcpClient.Session() {
                @Override public Set<String> tools() { return Set.of(
                        "context.list_tree", "context.search_code", "context.read_file",
                        "context.get_repository_rules", "context.get_dependencies", "context.get_symbols",
                        "evidence.get_summary", "evidence.read"); }
                @Override public String call(String toolName, Map<String, Object> arguments) {
                    lastArguments = arguments;
                    return "{\"ok\":true}";
                }
                @Override public void close() { }
            };
        }
    }
}
