package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.LlmMode;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class LlmGatewayClient {
    private final AiFactoryProperties props;
    private final WebClient client;

    public LlmGatewayClient(AiFactoryProperties props, WebClient.Builder builder) {
        this.props = props;
        this.client = builder.baseUrl(props.llmBaseUrl()).build();
    }

    public String chat(LlmMode mode, String system, String user) {
        if (mode == LlmMode.CLOUD && !props.cloudEnabled()) {
            throw new IllegalStateException("Cloud LLM is disabled by configuration");
        }
        Map<String, Object> body = Map.of(
                "model", mode == LlmMode.CLOUD ? props.cloudModel() : props.localModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                ));
        JsonNode response = client.post()
                .uri("/chat/completions")
                .headers(headers -> addAuthorization(headers))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMinutes(10));
        JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
        if (content == null || !content.isTextual()) {
            throw new IllegalStateException("Invalid response from LLM gateway");
        }
        return content.asText();
    }

    private void addAuthorization(HttpHeaders headers) {
        if (props.llmApiKey() != null && !props.llmApiKey().isBlank()) {
            headers.setBearerAuth(props.llmApiKey());
        }
    }
}
