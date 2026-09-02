package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextToolHostTest {
    @Test
    void injectsHostMetadataAndRejectsModelControlledIdentity() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        McpToolInvoker invoker = new McpToolInvoker() {
            public JsonNode call(String server, String tool, Map<String, Object> arguments) {
                captured.set(arguments);
                return new ObjectMapper().createObjectNode().put("ok", true);
            }
            public Availability availability(String server) { return new Availability(true, null); }
        };
        AgentContextToolHost host = new AgentContextToolHost(invoker,
                new McpFactoryProperties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE, "repository-context-mcp"),
                new OperationalKillSwitch((java.nio.file.Path) null));

        host.executor("task-180", "a".repeat(40), "planner").execute(
                new AgentToolLoop.ToolCall("call", "context.read_file", Map.of("path", "README.md")));

        assertEquals("task-180", captured.get().get("task_id"));
        assertEquals("planner", captured.get().get("actor"));
        assertTrue(captured.get().containsKey("traceparent"));
        assertThrows(AgentToolLoop.AgentLoopException.class, () -> host.executor(
                "task-180", "a".repeat(40), "planner").execute(new AgentToolLoop.ToolCall(
                "call", "context.read_file", Map.of("path", "README.md", "actor", "workflow"))));
    }
}
