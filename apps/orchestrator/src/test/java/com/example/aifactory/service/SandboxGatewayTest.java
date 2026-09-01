package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SandboxGatewayTest {
    @Test
    void activeModeDelegatesExclusivelyToMcp() throws Exception {
        McpFactoryProperties properties = McpSandboxServiceTest.properties(true,
                McpFactoryProperties.SandboxMode.MCP_ACTIVE);
        SandboxGateway gateway = new SandboxGateway(mcp(properties, "mcp"), properties);

        assertEquals("mcp", gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
    }

    @Test
    void activeModeRejectsDisabledMcp() {
        McpFactoryProperties properties = McpSandboxServiceTest.properties(false,
                McpFactoryProperties.SandboxMode.MCP_ACTIVE);
        SandboxGateway gateway = new SandboxGateway(mcp(properties, "mcp"), properties);

        assertThrows(IllegalStateException.class,
                () -> gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
    }

    @Test
    void disabledOperationFailsClosedWithoutDirectFallback() {
        McpFactoryProperties base = McpSandboxServiceTest.properties(true, McpFactoryProperties.SandboxMode.MCP_ACTIVE);
        McpFactoryProperties properties = new McpFactoryProperties(base.enabled(), base.repositoryContextMode(),
                base.repositoryContextServerName(), base.sandboxEnabled(), base.sandboxMode(), base.sandboxServerName(),
                base.sandboxPollInterval(), base.sandboxPollTimeout(), base.repositoryContextActiveRoles(),
                Set.of("run_tests"));
        SandboxGateway gateway = new SandboxGateway(mcp(properties, "mcp"), properties);

        assertThrows(IllegalStateException.class,
                () -> gateway.checkPatch(Path.of("unused"), "task-1", "a".repeat(40)));
    }

    @Test
    void obsoleteShadowModeFailsClosed() {
        McpFactoryProperties properties = McpSandboxServiceTest.properties(true,
                McpFactoryProperties.SandboxMode.MCP_SHADOW);
        SandboxGateway gateway = new SandboxGateway(mcp(properties, "mcp"), properties);

        assertThrows(IllegalStateException.class,
                () -> gateway.test(Path.of("unused"), "task-1", "a".repeat(40)));
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
