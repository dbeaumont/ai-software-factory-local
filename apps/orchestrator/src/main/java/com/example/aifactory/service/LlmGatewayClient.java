package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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

    public String chat(String system, String user, int maxTokens) {
        return chat(system, user, maxTokens, null);
    }

    String chat(String system, String user, int maxTokens, Map<String, Object> responseFormat) {
        if (!props.cloudEnabled()) {
            throw new IllegalStateException("Cloud LLM is disabled by configuration");
        }
        if (maxTokens < 1 || maxTokens > 8_192) {
            throw new IllegalArgumentException("maxTokens must be between 1 and 8192");
        }
        Map<String, Object> body = requestBody(props.cloudModel(), system, user, maxTokens, responseFormat);
        JsonNode response = client.post()
                .uri("/chat/completions")
                .headers(headers -> addAuthorization(headers))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMinutes(10));
        ChatCompletion completion = parseCompletion(response);
        log.info("LLM completion finished reason={} completion_tokens={}",
                completion.finishReason(), completion.completionTokens());
        return completion.content();
    }

    static ChatCompletion parseCompletion(JsonNode response) {
        JsonNode choice = response == null ? null : response.path("choices").path(0);
        JsonNode message = choice == null ? null : choice.path("message");
        String finishReason = choice == null ? "missing" : choice.path("finish_reason").asText("missing");
        int completionTokens = response == null ? -1 : response.path("usage").path("completion_tokens").asInt(-1);
        String refusal = message == null ? "" : message.path("refusal").asText("");
        if (!refusal.isBlank()) {
            throw new LlmCompletionException("refusal", false, "LLM refused the request");
        }
        if ("length".equals(finishReason)) {
            throw new LlmCompletionException("length", true, "LLM response was truncated at the output token limit");
        }
        if ("content_filter".equals(finishReason)) {
            throw new LlmCompletionException("content_filter", false, "LLM response was interrupted by the content filter");
        }
        if (!"stop".equals(finishReason)) {
            throw new LlmCompletionException(finishReason, false,
                    "LLM response ended with unsupported finish reason " + finishReason);
        }
        JsonNode content = message == null ? null : message.path("content");
        if (content == null || !content.isTextual()) {
            throw new LlmCompletionException("missing_content", false, "Invalid response from LLM gateway");
        }
        return new ChatCompletion(content.asText(), finishReason, completionTokens);
    }

    record ChatCompletion(String content, String finishReason, int completionTokens) {
    }

    static Map<String, Object> requestBody(String model, String system, String user, int maxTokens,
                                           Map<String, Object> responseFormat) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        body.put("max_tokens", maxTokens);
        if (responseFormat != null) {
            body.put("response_format", responseFormat);
        }
        return Map.copyOf(body);
    }

    static Map<String, Object> toolRequestBody(String model, List<Map<String, Object>> messages,
                                               List<ToolDefinition> tools, int maxTokens) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("At least one tool definition is required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.copyOf(messages));
        body.put("tools", tools.stream().map(tool -> Map.of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.inputSchema()))).toList());
        body.put("tool_choice", "auto");
        body.put("max_tokens", maxTokens);
        return Map.copyOf(body);
    }

    static ToolTurn parseToolTurn(JsonNode response) {
        JsonNode choice = response == null ? null : response.path("choices").path(0);
        JsonNode message = choice == null ? null : choice.path("message");
        String finishReason = choice == null ? "missing" : choice.path("finish_reason").asText("missing");
        int promptTokens = response == null ? -1 : response.path("usage").path("prompt_tokens").asInt(-1);
        int completionTokens = response == null ? -1 : response.path("usage").path("completion_tokens").asInt(-1);
        if (message == null || message.isMissingNode()) {
            throw new LlmCompletionException("missing_message", false, "Invalid tool response from LLM gateway");
        }
        List<ToolCall> calls = new java.util.ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                String id = call.path("id").asText("");
                String type = call.path("type").asText("");
                String name = call.path("function").path("name").asText("");
                String arguments = call.path("function").path("arguments").asText("");
                if (id.isBlank() || !"function".equals(type) || name.isBlank() || arguments.isBlank()) {
                    throw new LlmCompletionException("invalid_tool_call", false,
                            "LLM gateway altered or omitted tool call fields");
                }
                calls.add(new ToolCall(id, name, arguments));
            }
        }
        String content = message.path("content").isTextual() ? message.path("content").asText() : "";
        if (calls.isEmpty() && !"stop".equals(finishReason)) {
            throw new LlmCompletionException(finishReason, false,
                    "LLM gateway returned neither a final result nor a valid tool call");
        }
        return new ToolTurn(content, List.copyOf(calls), finishReason, promptTokens, completionTokens);
    }

    static Map<String, Object> toolResultMessage(String toolCallId, String result) {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId is required");
        }
        return Map.of("role", "tool", "tool_call_id", toolCallId, "content", result);
    }

    record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
    }

    record ToolCall(String id, String name, String arguments) {
    }

    record ToolTurn(String content, List<ToolCall> toolCalls, String finishReason,
                    int promptTokens, int completionTokens) {
    }

    public String modelName() {
        return props.cloudModel();
    }

    private void addAuthorization(HttpHeaders headers) {
        if (props.llmApiKey() != null && !props.llmApiKey().isBlank()) {
            headers.setBearerAuth(props.llmApiKey());
        }
    }
}
