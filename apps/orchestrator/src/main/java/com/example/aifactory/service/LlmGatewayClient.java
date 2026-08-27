package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import com.example.aifactory.model.LlmMode;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class LlmGatewayClient {
    private static final Duration CLOUD_AVAILABILITY_CACHE_TTL = Duration.ofSeconds(30);
    private final AiFactoryProperties props;
    private final WebClient client;
    private volatile CloudAvailability cachedCloudAvailability;
    private volatile Instant cloudAvailabilityCheckedAt = Instant.EPOCH;

    public LlmGatewayClient(AiFactoryProperties props, WebClient.Builder builder) {
        this.props = props;
        this.client = builder.baseUrl(props.llmBaseUrl()).build();
    }

    public synchronized CloudAvailability cloudAvailability() {
        if (!props.cloudEnabled()) {
            return CloudAvailability.unavailable("Le mode cloud est désactivé par la configuration de cette usine.");
        }
        if (cachedCloudAvailability != null
                && Instant.now().isBefore(cloudAvailabilityCheckedAt.plus(CLOUD_AVAILABILITY_CACHE_TTL))) {
            return cachedCloudAvailability;
        }

        try {
            Map<String, Object> body = Map.of(
                    "model", props.cloudModel(),
                    "messages", List.of(Map.of("role", "user", "content", "Reply with OK.")),
                    "max_tokens", 1);
            client.post()
                    .uri("/chat/completions")
                    .headers(this::addAuthorization)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block(Duration.ofSeconds(20));
                    cachedCloudAvailability = CloudAvailability.reachable();
        } catch (WebClientResponseException e) {
            cachedCloudAvailability = CloudAvailability.unavailable(
                    "L'accès à l'API LLM externe est refusé (HTTP %d). Vérifiez la politique réseau ou les droits du modèle."
                            .formatted(e.getStatusCode().value()));
        } catch (Exception e) {
            cachedCloudAvailability = CloudAvailability.unavailable(
                    "L'API LLM externe est inaccessible. Vérifiez la connexion réseau et le certificat du proxy.");
        }
        cloudAvailabilityCheckedAt = Instant.now();
        return cachedCloudAvailability;
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
