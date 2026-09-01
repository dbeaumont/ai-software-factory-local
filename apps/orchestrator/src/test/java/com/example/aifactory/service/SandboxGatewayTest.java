package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxGatewayTest {
    @Test
    void activeModeNeverCallsTheDirectDockerPath() throws Exception {
        AtomicInteger directCalls = new AtomicInteger();
        SandboxService direct = direct(directCalls, "direct");
        McpFactoryProperties properties = McpSandboxServiceTest.properties(true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);
        McpSandboxService mcp = mcp(properties, "mcp");
        SandboxGateway gateway = new SandboxGateway(direct, mcp, properties, new SimpleMeterRegistry());

        assertEquals("mcp", gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
        assertEquals(0, directCalls.get());
    }

    @Test
    void activeModeRejectsDisabledMcpInsteadOfFallingBackToDocker() {
        AtomicInteger directCalls = new AtomicInteger();
        McpFactoryProperties properties = McpSandboxServiceTest.properties(false, McpFactoryProperties.SandboxMode.MCP_ACTIVE);
        SandboxGateway gateway = new SandboxGateway(direct(directCalls, "direct"), mcp(properties, "mcp"), properties,
                new SimpleMeterRegistry());

        assertThrows(IllegalStateException.class,
                () -> gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
        assertEquals(0, directCalls.get());
    }

    @Test
    void shadowModeRecordsSuccessfulComparisonWithoutChangingDirectResult() throws Exception {
        AtomicInteger directCalls = new AtomicInteger();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        McpFactoryProperties properties = McpSandboxServiceTest.properties(true,
                McpFactoryProperties.SandboxMode.MCP_SHADOW);
        SandboxGateway gateway = new SandboxGateway(direct(directCalls, "same"), mcp(properties, "same"),
                properties, metrics);

        assertEquals("same", gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
        assertEquals(1, directCalls.get());
        assertEquals(1.0, metrics.counter("ai_factory_mcp_sandbox_shadow_runs", "operation", "validate_patch",
                "outcome", "success").count());
        assertEquals(1.0, metrics.counter("ai_factory_mcp_sandbox_shadow_comparisons", "operation",
                "validate_patch", "result", "equal").count());
        assertEquals(4.0, metrics.get("ai_factory_mcp_sandbox_shadow_chars")
                .tags("operation", "validate_patch", "source", "mcp").summary().mean());
    }

    private static SandboxService direct(AtomicInteger calls, String result) {
        return new SandboxService(null, null) {
            @Override
            public String checkPatch(Path workspace, String taskId, String sourceCommit) {
                calls.incrementAndGet();
                return result;
            }
        };
    }

    private static McpSandboxService mcp(McpFactoryProperties properties, String output) {
        ObjectMapper mapper = new ObjectMapper();
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                if (toolName.equals("sandbox.get_execution")) {
                    return mapper.valueToTree(Map.of(
                            "status", "SUCCEEDED", "verdict", "PASSED", "exit_code", 0, "output", output,
                            "output_total_chars", output.length(), "output_truncated", false,
                            "evidence_status", "COMPLETE", "output_digest", digest(output)));
                }
                return mapper.valueToTree(Map.of("execution_id", "1".repeat(32), "status", "ACCEPTED"));
            }

            @Override
            public Availability availability(String serverName) {
                return new Availability(true, null);
            }
        };
        return new McpSandboxService(invoker, properties, new SimpleMeterRegistry());
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
