package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmGatewayClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesACompleteChatCompletionWithoutRetainingPromptContent() throws Exception {
        LlmGatewayClient.ChatCompletion completion = LlmGatewayClient.parseCompletion(mapper.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"status\\":\\"IMPLEMENTABLE\\"}"}}],
                 "usage":{"completion_tokens":321}}
                """));

        assertEquals("stop", completion.finishReason());
        assertEquals(0, completion.promptTokens());
        assertEquals(321, completion.completionTokens());
        assertTrue(completion.content().contains("IMPLEMENTABLE"));
    }

    @Test
    void classifiesTruncationAsRetryable() throws Exception {
        LlmCompletionException exception = assertThrows(LlmCompletionException.class,
                () -> LlmGatewayClient.parseCompletion(mapper.readTree("""
                        {"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}
                        """)));

        assertEquals("length", exception.reason());
        assertTrue(exception.retryable());
    }

    @Test
    void classifiesRefusalAndContentFilterAsNonRetryable() throws Exception {
        LlmCompletionException refusal = assertThrows(LlmCompletionException.class,
                () -> LlmGatewayClient.parseCompletion(mapper.readTree("""
                        {"choices":[{"finish_reason":"stop","message":{"refusal":"cannot comply"}}]}
                        """)));
        LlmCompletionException contentFilter = assertThrows(LlmCompletionException.class,
                () -> LlmGatewayClient.parseCompletion(mapper.readTree("""
                        {"choices":[{"finish_reason":"content_filter","message":{"content":""}}]}
                        """)));

        assertEquals("refusal", refusal.reason());
        assertFalse(refusal.retryable());
        assertEquals("content_filter", contentFilter.reason());
        assertFalse(contentFilter.retryable());
    }

    @Test
    void preservesToolNameSchemaCallIdArgumentsAndResultAcrossOpenAiContract() throws Exception {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path"));
        Map<String, Object> request = LlmGatewayClient.toolRequestBody(
                "factory-code-cloud",
                List.of(Map.of("role", "system", "content", "Use the declared tools only.")),
                List.of(new LlmGatewayClient.ToolDefinition(
                        "context.read_file", "Read one repository file", schema)),
                512);

        Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) ((List<?>) request.get("tools")).getFirst()).get("function");
        assertEquals("context.read_file", function.get("name"));
        assertEquals(schema, function.get("parameters"));

        LlmGatewayClient.ToolTurn turn = LlmGatewayClient.parseToolTurn(mapper.readTree("""
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                  {"id":"call_mcp_170","type":"function","function":{
                    "name":"context.read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}
                ]}}],"usage":{"prompt_tokens":41,"completion_tokens":17}}
                """));

        assertEquals("tool_calls", turn.finishReason());
        assertEquals("call_mcp_170", turn.toolCalls().getFirst().id());
        assertEquals("context.read_file", turn.toolCalls().getFirst().name());
        assertEquals("{\"path\":\"README.md\"}", turn.toolCalls().getFirst().arguments());
        assertEquals(Map.of("role", "tool", "tool_call_id", "call_mcp_170", "content", "result-digest-170"),
                LlmGatewayClient.toolResultMessage(turn.toolCalls().getFirst().id(), "result-digest-170"));
    }

    @Test
    void rejectsToolCallsWhoseStableFieldsWereLost() throws Exception {
        LlmCompletionException exception = assertThrows(LlmCompletionException.class,
                () -> LlmGatewayClient.parseToolTurn(mapper.readTree("""
                        {"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
                          {"id":"","type":"function","function":{"name":"context.read_file","arguments":"{}"}}
                        ]}}]}
                        """)));

        assertEquals("invalid_tool_call", exception.reason());
    }
}
