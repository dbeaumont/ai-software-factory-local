package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class McpSandboxServiceTest {
    @TempDir
    Path workspace;

    @Test
    void startsPollsAndReturnsOnlyAPassedExecution() throws Exception {
        Files.writeString(workspace.resolve("changes.patch"), "patch");
        FakeInvoker invoker = new FakeInvoker("PASSED", 0, "tests passed");
        McpSandboxService service = service(invoker, true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        String output = service.test(workspace, "task-1", "a".repeat(40));

        assertEquals("tests passed", output);
        assertEquals(1, invoker.startCalls.get());
        assertTrue(invoker.pollCalls.get() >= 2);
        assertEquals("task-1", invoker.startArguments.get("task_id"));
        assertEquals("a".repeat(40), invoker.startArguments.get("source_commit"));
        assertTrue(invoker.startArguments.containsKey("idempotency_key"));
        assertTrue(invoker.startArguments.containsKey("patch_digest"));
    }

    @Test
    void failsClosedOnRejectedOrMalformedExecutions() throws Exception {
        Files.writeString(workspace.resolve("changes.patch"), "patch");
        McpSandboxService rejected = service(new FakeInvoker("REJECTED", 1, "tests failed"), true,
                McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> rejected.test(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("rejected"));
        assertTrue(error.getMessage().contains("tests failed"));
    }

    private static McpSandboxService service(McpToolInvoker invoker, boolean enabled,
                                             McpFactoryProperties.SandboxMode mode) {
        return new McpSandboxService(invoker, properties(enabled, mode), new SimpleMeterRegistry());
    }

    static McpFactoryProperties properties(boolean enabled, McpFactoryProperties.SandboxMode mode) {
        return new McpFactoryProperties(false, McpFactoryProperties.ContextMode.DIRECT, "repository-context-mcp",
                enabled, mode, "sandbox-execution-mcp", Duration.ofMillis(1), Duration.ofSeconds(2));
    }

    private static final class FakeInvoker implements McpToolInvoker {
        private final ObjectMapper mapper = new ObjectMapper();
        private final String verdict;
        private final int exitCode;
        private final String output;
        private final AtomicInteger startCalls = new AtomicInteger();
        private final AtomicInteger pollCalls = new AtomicInteger();
        private Map<String, Object> startArguments;

        private FakeInvoker(String verdict, int exitCode, String output) {
            this.verdict = verdict;
            this.exitCode = exitCode;
            this.output = output;
        }

        @Override
        public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
            if (!toolName.equals("sandbox.get_execution")) {
                startCalls.incrementAndGet();
                startArguments = arguments;
                return mapper.valueToTree(Map.of("execution_id", "1".repeat(32), "status", "ACCEPTED"));
            }
            if (pollCalls.incrementAndGet() == 1) {
                return mapper.valueToTree(Map.of("status", "RUNNING"));
            }
            return mapper.valueToTree(Map.of(
                    "status", "SUCCEEDED", "verdict", verdict, "exit_code", exitCode, "output", output));
        }

        @Override
        public Availability availability(String serverName) {
            return new Availability(true, null);
        }
    }
}
