package com.example.aifactory.service;

import com.example.aifactory.config.AssuranceClientProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssuranceGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyPassedStructuredDecision() {
        AssuranceGateway passed = gateway("PASSED");
        assertEquals("PASSED", passed.requireQualityGate("task-1", "a".repeat(40),
                "QUALITY GATE STATUS: PASSED").path("verdict").asText());
        assertThrows(IllegalStateException.class, () -> gateway("INDETERMINATE")
                .requireQualityGate("task-1", "a".repeat(40), "ANALYSIS SUCCESSFUL"));
    }

    private AssuranceGateway gateway(String verdict) {
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                assertEquals("assurance-mcp", serverName);
                assertEquals("assurance.evaluate_quality_gate", toolName);
                return mapper.valueToTree(Map.of("verdict", verdict));
            }

            @Override
            public Availability availability(String serverName) { return new Availability(true, null); }
        };
        McpResponseValidator validator = mock(McpResponseValidator.class);
        when(validator.validate(eq("assurance.evaluate_quality_gate"), any())).thenAnswer(call -> call.getArgument(1));
        return new AssuranceGateway(invoker, validator, new AssuranceClientProperties(true, "assurance-mcp"));
    }
}
