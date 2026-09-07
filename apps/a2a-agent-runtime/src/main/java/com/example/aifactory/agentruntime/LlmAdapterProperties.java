package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ai-factory.agent-runtime.llm")
public record LlmAdapterProperties(String baseUrl, String apiKey, String provider, String model,
                                   int maxOutputTokens, Duration timeout) {
    public LlmAdapterProperties {
        if (baseUrl == null || baseUrl.isBlank() || provider == null || !provider.matches("[a-z0-9_-]{1,64}")
                || model == null || model.isBlank()) {
            throw new IllegalArgumentException("LLM base URL, bounded provider and model are required");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 8_192) {
            throw new IllegalArgumentException("LLM max output tokens must be between 1 and 8192");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("LLM timeout must be positive");
        }
    }
}
