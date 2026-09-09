package com.example.aifactory.agentruntime;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSdkSessionFactoryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsMcpProtocolErrorsAsBoundedModelVisibleData() throws Exception {
        String untrusted = "invalid arguments " + "x".repeat(2_000);

        String result = McpSdkSessionFactory.modelVisibleError(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(untrusted)), true, null, Map.of()),
                mapper);

        JsonNode json = mapper.readTree(result);
        assertTrue(json.path("is_error").asBoolean());
        assertTrue(json.path("message").asText().startsWith("invalid arguments"));
        assertEquals(1_000, json.path("message").asText().length());
    }
}
