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
                if (toolName.equals("context.list_tree")) {
                    return mapper.valueToTree(Map.of("entries", List.of(
                            Map.of("path", "src/Application.java", "type", "file"),
                            Map.of("path", "image.png", "type", "file"))));
                }
                return mapper.valueToTree(Map.of("path", "src/Application.java", "content", "class Application {}\n"));
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
        assertEquals(2, calls.get());
    }
}
