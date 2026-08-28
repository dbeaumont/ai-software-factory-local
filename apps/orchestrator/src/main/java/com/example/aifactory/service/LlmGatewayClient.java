package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import com.example.aifactory.model.LlmMode;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class LlmGatewayClient {
    private static final Duration CLOUD_AVAILABILITY_CACHE_TTL = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(LlmGatewayClient.class);
    private final AiFactoryProperties props;
    private final WebClient client;
    private volatile CloudAvailability cachedCloudAvailability;
    private volatile Instant cloudAvailabilityCheckedAt = Instant.EPOCH;

    public LlmGatewayClient(AiFactoryProperties props, WebClient.Builder builder) {
        this.props = props;
        this.client = builder.baseUrl(props.llmBaseUrl()).build();
    }

    /**
     * Checks the external model without blocking the HTTP request thread.
     *
     * <p>The capabilities endpoint is reactive. Blocking its subscription made a
     * cancelled browser request interrupt the probe and incorrectly cache the
     * interruption as an external LLM outage.</p>
     */
    public Mono<CloudAvailability> cloudAvailabilityAsync() {
        if (!props.cloudEnabled()) {
            return Mono.just(CloudAvailability.unavailable(
                    "Le mode cloud est désactivé par la configuration de cette usine."));
        }
        if (cachedCloudAvailability != null
                && Instant.now().isBefore(cloudAvailabilityCheckedAt.plus(CLOUD_AVAILABILITY_CACHE_TTL))) {
            return Mono.just(cachedCloudAvailability);
        }

        Map<String, Object> body = Map.of(
                "model", props.cloudModel(),
                "messages", List.of(Map.of("role", "user", "content", "Reply with OK.")),
                "max_tokens", 1);
        return client.post()
                .uri("/chat/completions")
                .headers(this::addAuthorization)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .thenReturn(CloudAvailability.reachable())
                .timeout(Duration.ofSeconds(20))
                .onErrorResume(WebClientResponseException.class, e -> Mono.just(CloudAvailability.unavailable(
                        "L'accès à l'API LLM externe est refusé (HTTP %d). Vérifiez la politique réseau ou les droits du modèle."
                                .formatted(e.getStatusCode().value()))))
                .onErrorResume(e -> {
                    log.warn("Cloud availability probe failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                    return Mono.just(CloudAvailability.unavailable(
                            "L'API LLM externe est inaccessible. Vérifiez la connexion réseau et le certificat du proxy."));
                })
                .doOnNext(this::cacheReachableCloudAvailability);
    }

    public CloudAvailability cloudAvailability() {
        return cloudAvailabilityAsync().block(Duration.ofSeconds(25));
    }

    private void cacheReachableCloudAvailability(CloudAvailability availability) {
        if (availability.available()) {
            cachedCloudAvailability = availability;
            cloudAvailabilityCheckedAt = Instant.now();
        }
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

    public String modelName(LlmMode mode) {
        return mode == LlmMode.CLOUD ? props.cloudModel() : props.localModel();
    }

    private void addAuthorization(HttpHeaders headers) {
        if (props.llmApiKey() != null && !props.llmApiKey().isBlank()) {
            headers.setBearerAuth(props.llmApiKey());
        }
    }
}
