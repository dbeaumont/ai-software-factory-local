package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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
}
