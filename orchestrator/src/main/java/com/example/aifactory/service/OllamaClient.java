package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class OllamaClient {
    private final AiFactoryProperties props;
    private final WebClient client;

    public OllamaClient(AiFactoryProperties props, WebClient.Builder builder) {
        this.props = props;
        this.client = builder.baseUrl(props.ollamaBaseUrl()).build();
    }

    public String chat(String system, String user) {
        Map<String, Object> body = Map.of(
                "model", props.ollamaModel(),
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)
                ),
                "options", Map.of("temperature", 0.2)
        );
        JsonNode response = client.post()
                .uri("/api/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMinutes(10));
        if (response == null || response.path("message").path("content").isMissingNode()) {
            throw new IllegalStateException("Invalid response from Ollama");
        }
        return response.path("message").path("content").asText();
    }
}
