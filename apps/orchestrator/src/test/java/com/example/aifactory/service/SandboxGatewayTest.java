package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
        SandboxGateway gateway = new SandboxGateway(direct, mcp, properties);

        assertEquals("mcp", gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
        assertEquals(0, directCalls.get());
    }

    @Test
    void activeModeRejectsDisabledMcpInsteadOfFallingBackToDocker() {
        AtomicInteger directCalls = new AtomicInteger();
        McpFactoryProperties properties = McpSandboxServiceTest.properties(false, McpFactoryProperties.SandboxMode.MCP_ACTIVE);
        SandboxGateway gateway = new SandboxGateway(direct(directCalls, "direct"), mcp(properties, "mcp"), properties);

        assertThrows(IllegalStateException.class,
                () -> gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
        assertEquals(0, directCalls.get());
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
                            "status", "SUCCEEDED", "verdict", "PASSED", "exit_code", 0, "output", output));
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
}
