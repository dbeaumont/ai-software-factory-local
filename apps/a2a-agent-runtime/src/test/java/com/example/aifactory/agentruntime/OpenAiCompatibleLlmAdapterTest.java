package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import com.example.aifactory.agentcore.LlmCompletionException;
import com.example.aifactory.agentcore.LlmCompletionPort;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleLlmAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmCompletionPort.ToolDefinition readFile = new LlmCompletionPort.ToolDefinition(
            "context.read_file", "Read one repository file",
            Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"))));

    @Test
    void preservesModelMessagesToolSchemaAliasAndOutputBudget() {
        var aliases = OpenAiCompatibleLlmAdapter.aliases(List.of(readFile));
        Map<String, Object> request = OpenAiCompatibleLlmAdapter.requestBody("factory-code-cloud",
                List.of(Map.of("role", "system", "content", "system")), List.of(readFile), 8192, aliases);

        assertEquals("factory-code-cloud", request.get("model"));
        assertEquals(8192, request.get("max_tokens"));
        assertEquals("auto", request.get("tool_choice"));
        Map<?, ?> function = (Map<?, ?>) ((Map<?, ?>) ((List<?>) request.get("tools")).getFirst()).get("function");
        assertEquals("mcp_0_context_read_file", function.get("name"));
        assertEquals(readFile.inputSchema(), function.get("parameters"));
    }

    @Test
    void preservesToolCallIdentityArgumentsAndUsage() throws Exception {
        var aliases = OpenAiCompatibleLlmAdapter.aliases(List.of(readFile));
        AgentLoop.Turn turn = OpenAiCompatibleLlmAdapter.parse(mapper.readTree("""
                {"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[
                  {"id":"call-170","type":"function","function":{
                    "name":"mcp_0_context_read_file","arguments":"{\\"path\\":\\"README.md\\"}"}}
                ]}}],"usage":{"prompt_tokens":41,"completion_tokens":17},"response_cost":0.000123}
                """), List.of(readFile), aliases, mapper);

        assertEquals(AgentLoop.Stop.TOOL_CALLS, turn.stop());
        assertEquals("call-170", turn.toolCalls().getFirst().id());
        assertEquals("context.read_file", turn.toolCalls().getFirst().name());
        assertEquals(Map.of("path", "README.md"), turn.toolCalls().getFirst().arguments());
        assertEquals(41, turn.promptTokens());
        assertEquals(17, turn.completionTokens());
        assertEquals(123, turn.costMicros());
    }

    @Test
    void preservesFinalJsonAndStableFailureClassification() throws Exception {
        AgentLoop.Turn result = OpenAiCompatibleLlmAdapter.parse(mapper.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":"{\\"status\\":\\"OK\\"}"}}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}
                """), List.of(), OpenAiCompatibleLlmAdapter.aliases(List.of()), mapper);
        assertEquals(AgentLoop.Stop.FINAL, result.stop());
        assertEquals("{\"status\":\"OK\"}", result.finalResult());

        LlmCompletionException truncated = assertThrows(LlmCompletionException.class,
                () -> OpenAiCompatibleLlmAdapter.parse(mapper.readTree("""
                        {"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}
                        """), List.of(), OpenAiCompatibleLlmAdapter.aliases(List.of()), mapper));
        assertEquals("length", truncated.reason());
        assertTrue(truncated.retryable());

        LlmCompletionException refused = assertThrows(LlmCompletionException.class,
                () -> OpenAiCompatibleLlmAdapter.parse(mapper.readTree("""
                        {"choices":[{"finish_reason":"stop","message":{"refusal":"no"}}]}
                        """), List.of(), OpenAiCompatibleLlmAdapter.aliases(List.of()), mapper));
        assertFalse(refused.retryable());
    }

    @Test
    void rejectsInvalidAdapterBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new LlmAdapterProperties(
                "http://litellm:4000/v1", "", "litellm", "model", 8193, Duration.ofMinutes(10)));
    }
}
