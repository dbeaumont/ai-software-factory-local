package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRepositoryContextServiceTest {
    @Test
    void collectsOnlyAcceptedFilesFromStructuredToolResults() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger calls = new AtomicInteger();
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                calls.incrementAndGet();
                assertEquals("1", arguments.get("schema_version"));
                assertEquals("task-1", arguments.get("task_id"));
                assertEquals("a".repeat(40), arguments.get("source_commit"));
                assertTrue(arguments.containsKey("trace_id"));
                assertTrue(arguments.containsKey("traceparent"));
                assertTrue(arguments.containsKey("attempt_id"));
                assertTrue(arguments.containsKey("deadline"));
                if (toolName.equals("context.list_tree")) {
                    return mapper.valueToTree(Map.of("entries", List.of(
                            Map.of("path", "src/Application.java", "type", "file"),
                            Map.of("path", "image.png", "type", "file"))));
                }
                return mapper.valueToTree(Map.of("path", "src/Application.java", "content", "class Application {}\n",
                        "sha256", "b".repeat(64)));
            }

            @Override
            public Availability availability(String serverName) {
                return new Availability(true, null);
            }
        };
        McpRepositoryContextService service = new McpRepositoryContextService(invoker,
                new McpFactoryProperties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE, "repository-context-mcp"),
                new SimpleMeterRegistry());

        String context = service.collect(Path.of("unused"), "task-1", "a".repeat(40));

        assertTrue(context.contains("src/Application.java"));
        assertTrue(context.contains("class Application"));
        assertTrue(context.contains("SOURCE: repo://task-1/" + "a".repeat(40)
                + "/src%2FApplication.java#sha256=" + "b".repeat(64)));
        assertEquals(2, calls.get());
    }

    @Test
    void followsBoundedTreePaginationUntilItFindsUsefulFiles() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger listCalls = new AtomicInteger();
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                if (toolName.equals("context.list_tree")) {
                    if (listCalls.getAndIncrement() == 0) {
                        return mapper.valueToTree(Map.of("entries", List.of(
                                Map.of("path", "assets/image.png", "type", "file")),
                                "next_cursor", "opaque-cursor"));
                    }
                    assertEquals("opaque-cursor", arguments.get("cursor"));
                    return mapper.valueToTree(Map.of("entries", List.of(
                            Map.of("path", "src/Later.java", "type", "file"))));
                }
                return mapper.valueToTree(Map.of("content", "class Later {}", "sha256", "c".repeat(64)));
            }

            @Override
            public Availability availability(String serverName) {
                return new Availability(true, null);
            }
        };
        McpRepositoryContextService service = new McpRepositoryContextService(invoker,
                new McpFactoryProperties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE,
                        "repository-context-mcp"), new SimpleMeterRegistry());

        String context = service.collect(Path.of("unused"), "task-1", "a".repeat(40));

        assertTrue(context.contains("src/Later.java"));
        assertEquals(2, listCalls.get());
    }
}
