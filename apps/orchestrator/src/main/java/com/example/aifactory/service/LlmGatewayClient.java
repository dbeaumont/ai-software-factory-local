package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmGatewayClient {
    private static final Duration CLOUD_AVAILABILITY_CACHE_TTL = Duration.ofSeconds(30);
    private static final Logger log = LoggerFactory.getLogger(LlmGatewayClient.class);
    private final AiFactoryProperties props;
    private final WebClient client;
    private final ObjectMapper objectMapper;
    private volatile CloudAvailability cachedCloudAvailability;
    private volatile Instant cloudAvailabilityCheckedAt = Instant.EPOCH;

    public LlmGatewayClient(AiFactoryProperties props, WebClient.Builder builder, ObjectMapper objectMapper) {
        this.props = props;
        this.client = builder.baseUrl(props.llmBaseUrl()).build();
        this.objectMapper = objectMapper;
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

    private void cacheReachableCloudAvailability(CloudAvailability availability) {
        if (availability.available()) {
            cachedCloudAvailability = availability;
            cloudAvailabilityCheckedAt = Instant.now();
        }
    }

    public String chat(String system, String user, int maxTokens) {
        return chat(system, user, maxTokens, null);
    }

    AgentToolLoop.Turn nextToolTurn(List<AgentToolLoop.Message> messages,
                                    List<ToolDefinition> tools, int maxTokens) {
        ToolNameMappings toolNames = toolNameMappings(tools);
        List<Map<String, Object>> wireMessages = messages.stream()
                .map(message -> wireMessage(message, toolNames.canonicalToWire()))
                .toList();
        JsonNode response = client.post()
                .uri("/chat/completions")
                .headers(this::addAuthorization)
                .bodyValue(toolRequestBody(props.cloudModel(), wireMessages, tools, maxTokens))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(Duration.ofMinutes(10));
        ToolTurn turn = parseToolTurn(response);
        List<AgentToolLoop.ToolCall> calls = turn.toolCalls().stream().map(call -> {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = objectMapper.readValue(call.arguments(), Map.class);
                String canonicalName = canonicalToolName(tools, call.name());
                return new AgentToolLoop.ToolCall(call.id(), canonicalName, arguments);
            } catch (Exception exception) {
                if (exception instanceof LlmCompletionException completionException) {
                    throw completionException;
                }
                throw new LlmCompletionException("invalid_tool_arguments", false,
                        "LLM returned invalid JSON tool arguments");
            }
        }).toList();
        long costMicros = response == null ? 0 : Math.max(0, Math.round(
                response.path("_hidden_params").path("response_cost").asDouble(
                        response.path("response_cost").asDouble(0)) * 1_000_000));
        AgentToolLoop.Stop stop = calls.isEmpty() ? AgentToolLoop.Stop.FINAL : AgentToolLoop.Stop.TOOL_CALLS;
        return new AgentToolLoop.Turn(stop, calls.isEmpty() ? turn.content() : null, calls,
                Math.max(0, turn.promptTokens()), Math.max(0, turn.completionTokens()), costMicros);
    }

    private Map<String, Object> wireMessage(AgentToolLoop.Message message,
                                            Map<String, String> canonicalToWire) {
        if ("assistant".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            return Map.of("role", "assistant", "content", "", "tool_calls", message.toolCalls().stream().map(call -> Map.of(
                    "id", call.id(), "type", "function", "function", Map.of(
                            "name", requiredWireName(call.name(), canonicalToWire),
                            "arguments", writeArguments(call.arguments())))).toList());
        }
        if ("tool".equals(message.role()) && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            return Map.of("role", "tool", "tool_call_id", message.toolCalls().getFirst().id(),
                    "content", message.content());
        }
        return Map.of("role", message.role(), "content", message.content() == null ? "" : message.content());
    }

    private String writeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize tool arguments", exception);
        }
    }

    String chat(String system, String user, int maxTokens, Map<String, Object> responseFormat) {
        return chatDetailed(system, user, maxTokens, responseFormat).content();
    }

    LlmCallResult chatDetailed(String system, String user, int maxTokens, Map<String, Object> responseFormat) {
        if (!props.cloudEnabled()) {
            throw new IllegalStateException("Cloud LLM is disabled by configuration");
        }
        if (maxTokens < 1 || maxTokens > 8_192) {
            throw new IllegalArgumentException("maxTokens must be between 1 and 8192");
        }
        Map<String, Object> body = requestBody(props.cloudModel(), system, user, maxTokens, responseFormat);
        long started = System.nanoTime();
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
        return new LlmCallResult(completion.content(), completion.promptTokens() + completion.completionTokens(),
                completion.costMicros(), Duration.ofNanos(System.nanoTime() - started).toMillis());
    }

    static ChatCompletion parseCompletion(JsonNode response) {
        JsonNode choice = response == null ? null : response.path("choices").path(0);
        JsonNode message = choice == null ? null : choice.path("message");
        String finishReason = choice == null ? "missing" : choice.path("finish_reason").asText("missing");
        int completionTokens = response == null ? -1 : response.path("usage").path("completion_tokens").asInt(-1);
        int promptTokens = response == null ? -1 : response.path("usage").path("prompt_tokens").asInt(-1);
        long costMicros = response == null ? 0 : Math.max(0, Math.round(
                response.path("_hidden_params").path("response_cost").asDouble(
                        response.path("response_cost").asDouble(0)) * 1_000_000));
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
        return new ChatCompletion(content.asText(), finishReason, Math.max(0, promptTokens),
                Math.max(0, completionTokens), costMicros);
    }

    record ChatCompletion(String content, String finishReason, int promptTokens, int completionTokens,
                          long costMicros) {
    }

    record LlmCallResult(String content, long tokens, long costMicros, long durationMillis) {
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
        ToolNameMappings toolNames = toolNameMappings(tools);
        body.put("tools", tools.stream().map(tool -> {
            if (tool.name() == null || !tool.name().matches("[a-z][a-z0-9_-]{0,63}\\.[a-z][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Invalid namespaced tool name");
            }
            String description = tool.description() == null ? "" : tool.description()
                    .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").strip();
            if (description.length() > 300) description = description.substring(0, 300);
            return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", toolNames.canonicalToWire().get(tool.name()),
                        "description", "UNTRUSTED TOOL METADATA: " + description,
                        "parameters", tool.inputSchema()));
        }).toList());
        body.put("tool_choice", "auto");
        body.put("max_tokens", maxTokens);
        return Map.copyOf(body);
    }

    private static ToolNameMappings toolNameMappings(List<ToolDefinition> tools) {
        Map<String, String> canonicalToWire = new LinkedHashMap<>();
        Map<String, String> wireToCanonical = new HashMap<>();
        for (int index = 0; index < tools.size(); index++) {
            ToolDefinition tool = tools.get(index);
            if (tool.name() == null || !tool.name().matches("[a-z][a-z0-9_-]{0,63}\\.[a-z][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Invalid namespaced tool name");
            }
            if (canonicalToWire.containsKey(tool.name())) {
                throw new IllegalArgumentException("Duplicate tool name");
            }
            String readableName = tool.name().replace('.', '_');
            String wireName = "mcp_" + index + "_" + readableName;
            if (wireName.length() > 64) {
                wireName = wireName.substring(0, 64);
            }
            canonicalToWire.put(tool.name(), wireName);
            wireToCanonical.put(wireName, tool.name());
        }
        return new ToolNameMappings(Map.copyOf(canonicalToWire), Map.copyOf(wireToCanonical));
    }

    private static String requiredWireName(String canonicalName, Map<String, String> canonicalToWire) {
        String wireName = canonicalToWire.get(canonicalName);
        if (wireName == null) {
            throw new LlmCompletionException("unknown_tool", false,
                    "Tool conversation contains an undeclared tool name");
        }
        return wireName;
    }

    static String canonicalToolName(List<ToolDefinition> tools, String wireName) {
        String canonicalName = toolNameMappings(tools).wireToCanonical().get(wireName);
        if (canonicalName == null) {
            throw new LlmCompletionException("unknown_tool", false,
                    "LLM returned an undeclared tool name");
        }
        return canonicalName;
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

    private record ToolNameMappings(Map<String, String> canonicalToWire,
                                    Map<String, String> wireToCanonical) {
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
