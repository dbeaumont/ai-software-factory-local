package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.HexFormat;
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
        assertTrue(invoker.startArguments.containsKey("attempt_id"));
        assertTrue(invoker.startArguments.containsKey("traceparent"));
        assertTrue(invoker.startArguments.containsKey("deadline"));
        assertTrue(invoker.startArguments.containsKey("idempotency_key"));
        assertTrue(invoker.startArguments.get("idempotency_key").toString()
                .matches("task-1:[0-9a-f]{32}:run-tests:[0-9a-f]{64}"));
        assertTrue(invoker.startArguments.containsKey("patch_digest"));
        assertEquals(16_384, invoker.lastLookupArguments.get("output_limit"));
    }

    @Test
    void reassemblesBoundedPaginatedOutput() throws Exception {
        String expected = "page-" + "x".repeat(40_000);
        FakeInvoker invoker = new FakeInvoker("PASSED", 0, expected);
        McpSandboxService service = service(invoker, true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        String output = service.test(workspace, "task-1", "a".repeat(40));

        assertEquals(expected, output);
        assertTrue(invoker.pollCalls.get() >= 4);
    }

    @Test
    void rejectsInvalidPatchEvidence() throws Exception {
        Files.writeString(workspace.resolve("changes.patch"), "patch");
        McpSandboxService rejected = service(new FakeInvoker("REJECTED", 1, "error: corrupt patch at line 4"), true,
                McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> rejected.checkPatch(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("validate-patch rejected"));
        assertTrue(error.getMessage().contains("corrupt patch"));
    }

    @Test
    void rejectsFailedTestEvidence() {
        McpSandboxService rejected = service(new FakeInvoker("REJECTED", 1, "Tests run: 3, Failures: 1"), true,
                McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> rejected.test(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("run-tests rejected"));
        assertTrue(error.getMessage().contains("Failures: 1"));
    }

    @Test
    void rejectsMissingSonarEvidence() {
        McpSandboxService rejected = service(new FakeInvoker("REJECTED", 2,
                "Required Sonar token is unavailable"), true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> rejected.quality(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("run-quality rejected"));
        assertTrue(error.getMessage().contains("Sonar token is unavailable"));
    }

    @Test
    void rejectsBlockingVulnerabilityEvidence() {
        McpSandboxService rejected = service(new FakeInvoker("REJECTED", 1,
                "Total: 1 (HIGH: 1, CRITICAL: 0)"), true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> rejected.security(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("run-security rejected"));
        assertTrue(error.getMessage().contains("HIGH: 1"));
    }

    @Test
    void failsClosedOnAStaleRunningHeartbeat() {
        FakeInvoker invoker = new FakeInvoker("PASSED", 0, "unused");
        invoker.heartbeatAt = Instant.EPOCH;
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        McpSandboxService service = new McpSandboxService(invoker,
                properties(true, McpFactoryProperties.SandboxMode.MCP_ACTIVE), metrics);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.test(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("heartbeat is stale"));
        assertEquals(1, metrics.get("ai_factory_sandbox_heartbeat_invalid").counter().count());
    }

    @Test
    void failsClosedOnPartialEvidence() {
        FakeInvoker invoker = new FakeInvoker("INDETERMINATE", 0, "truncated output");
        invoker.evidenceStatus = "PARTIAL";
        invoker.outputTruncated = true;
        McpSandboxService service = service(invoker, true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.test(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("partial evidence"));
        assertTrue(error.getMessage().contains("evidence_status=PARTIAL"));
        assertTrue(error.getMessage().contains("output_truncated=true"));
        assertTrue(error.getMessage().contains("retained_chars=16"));
    }

    @Test
    void failsClosedOnTamperedOutputDigest() {
        FakeInvoker invoker = new FakeInvoker("PASSED", 0, "tests passed");
        invoker.outputDigest = "0".repeat(64);
        McpSandboxService service = service(invoker, true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.test(workspace, "task-1", "a".repeat(40)));

        assertTrue(error.getMessage().contains("digest mismatch"));
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
        private Map<String, Object> lastLookupArguments;
        private Instant heartbeatAt = Instant.now();
        private String evidenceStatus = "COMPLETE";
        private String outputDigest;
        private boolean outputTruncated;

        private FakeInvoker(String verdict, int exitCode, String output) {
            this.verdict = verdict;
            this.exitCode = exitCode;
            this.output = output;
            this.outputDigest = digest(output);
        }

        @Override
        public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
            if (!toolName.equals("sandbox.get_execution")) {
                startCalls.incrementAndGet();
                startArguments = arguments;
                return mapper.valueToTree(Map.of("execution_id", "1".repeat(32), "status", "ACCEPTED"));
            }
            lastLookupArguments = arguments;
            if (pollCalls.incrementAndGet() == 1) {
                return mapper.valueToTree(Map.of(
                        "status", "RUNNING", "heartbeat_at", heartbeatAt.toString()));
            }
            int cursor = ((Number) arguments.getOrDefault("output_cursor", 0)).intValue();
            int limit = ((Number) arguments.getOrDefault("output_limit", 4_096)).intValue();
            int end = Math.min(output.length(), cursor + limit);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "SUCCEEDED");
            response.put("verdict", verdict);
            response.put("exit_code", exitCode);
            response.put("output", output.substring(cursor, end));
            response.put("output_cursor", cursor);
            response.put("output_total_chars", output.length());
            response.put("output_truncated", outputTruncated);
            response.put("evidence_status", evidenceStatus);
            response.put("output_digest", outputDigest);
            response.put("next_output_cursor", end < output.length() ? end : null);
            return mapper.valueToTree(response);
        }

        private static String digest(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public Availability availability(String serverName) {
            return new Availability(true, null);
        }
    }
}
