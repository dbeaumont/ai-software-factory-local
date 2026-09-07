package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import com.example.aifactory.agentcore.LlmCompletionException;
import com.example.aifactory.agentcore.LlmCompletionPort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

/** Synchronous worker-side adapter for the OpenAI-compatible chat-completions contract. */
final class OpenAiCompatibleLlmAdapter implements LlmCompletionPort {
    private final WebClient client;
    private final ObjectMapper mapper;
    private final LlmAdapterProperties properties;
    private final LlmMetrics metrics;

    OpenAiCompatibleLlmAdapter(WebClient.Builder builder, ObjectMapper mapper, LlmAdapterProperties properties) {
        this(builder, mapper, properties, null);
    }

    OpenAiCompatibleLlmAdapter(WebClient.Builder builder, ObjectMapper mapper, LlmAdapterProperties properties,
                                LlmMetrics metrics) {
        this.client = builder.baseUrl(properties.baseUrl()).build();
        this.mapper = mapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public AgentLoop.Turn nextTurn(List<AgentLoop.Message> messages, List<ToolDefinition> tools,
                                   int maxOutputTokens) {
        int boundedTokens = Math.min(maxOutputTokens, properties.maxOutputTokens());
        if (boundedTokens < 1) throw new IllegalArgumentException("A positive output token budget is required");
        ToolAliases aliases = aliases(tools);
        List<Map<String, Object>> wireMessages = messages.stream()
                .map(message -> wireMessage(message, aliases.canonicalToWire())).toList();
        Instant startedAt = Instant.now();
        JsonNode response = null;
        AgentLoop.Turn turn = null;
        String outcome = "error";
        try {
            response = client.post().uri("/chat/completions")
                    .headers(headers -> authorize(headers, properties.apiKey()))
                    .bodyValue(requestBody(properties.model(), wireMessages, tools, boundedTokens, aliases))
                    .retrieve().bodyToMono(JsonNode.class).block(properties.timeout());
            turn = parse(response, tools, aliases, mapper);
            outcome = "success";
            return turn;
        } catch (LlmCompletionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (isTimeout(exception)) outcome = "timeout";
            throw new LlmCompletionException("provider_transport", true,
                    "LLM provider request failed", exception);
        } finally {
            if (metrics != null) metrics.record(outcome, Duration.between(startedAt, Instant.now()), turn, response);
        }
    }

    static Map<String, Object> requestBody(String model, List<Map<String, Object>> messages,
                                           List<ToolDefinition> tools, int maxTokens, ToolAliases aliases) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.copyOf(messages));
        body.put("max_tokens", maxTokens);
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(tool -> Map.of(
                    "type", "function", "function", Map.of(
                            "name", aliases.canonicalToWire().get(tool.name()),
                            "description", boundedDescription(tool.description()),
                            "parameters", tool.inputSchema()))).toList());
            body.put("tool_choice", "auto");
        }
        return Map.copyOf(body);
    }

    static AgentLoop.Turn parse(JsonNode response, List<ToolDefinition> tools,
                                ToolAliases aliases, ObjectMapper mapper) {
        JsonNode choice = response == null ? null : response.path("choices").path(0);
        JsonNode message = choice == null ? null : choice.path("message");
        String finishReason = choice == null ? "missing" : choice.path("finish_reason").asText("missing");
        if (message == null || message.isMissingNode()) {
            throw failure("missing_message", false, "LLM response has no message");
        }
        String refusal = message.path("refusal").asText("");
        if (!refusal.isBlank()) throw failure("refusal", false, "LLM refused the request");
        if ("length".equals(finishReason)) throw failure("length", true, "LLM response was truncated");
        if ("content_filter".equals(finishReason)) {
            throw failure("content_filter", false, "LLM response was filtered");
        }
        List<AgentLoop.ToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) for (JsonNode call : toolCalls) {
            String id = call.path("id").asText("");
            String type = call.path("type").asText("");
            String wireName = call.path("function").path("name").asText("");
            String arguments = call.path("function").path("arguments").asText("");
            String canonical = aliases.wireToCanonical().get(wireName);
            if (id.isBlank() || !"function".equals(type) || canonical == null || arguments.isBlank()) {
                throw failure("invalid_tool_call", false, "LLM returned an invalid or undeclared tool call");
            }
            try {
                @SuppressWarnings("unchecked") Map<String, Object> parsed = mapper.readValue(arguments, Map.class);
                calls.add(new AgentLoop.ToolCall(id, canonical, parsed));
            } catch (Exception exception) {
                throw new LlmCompletionException("invalid_tool_arguments", false,
                        "LLM returned invalid JSON tool arguments", exception);
            }
        }
        String content = message.path("content").isTextual() ? message.path("content").asText() : "";
        if (calls.isEmpty() && !"stop".equals(finishReason)) {
            throw failure(finishReason, false, "LLM returned neither a final result nor tool calls");
        }
        int promptTokens = Math.max(0, response.path("usage").path("prompt_tokens").asInt(0));
        int completionTokens = Math.max(0, response.path("usage").path("completion_tokens").asInt(0));
        long costMicros = Math.max(0, Math.round(response.path("_hidden_params").path("response_cost")
                .asDouble(response.path("response_cost").asDouble(0)) * 1_000_000));
        AgentLoop.Stop stop = calls.isEmpty() ? AgentLoop.Stop.FINAL : AgentLoop.Stop.TOOL_CALLS;
        return new AgentLoop.Turn(stop, calls.isEmpty() ? content : null, calls,
                promptTokens, completionTokens, costMicros);
    }

    static ToolAliases aliases(List<ToolDefinition> tools) {
        Map<String, String> canonicalToWire = new LinkedHashMap<>();
        Map<String, String> wireToCanonical = new HashMap<>();
        for (int index = 0; index < tools.size(); index++) {
            String canonical = tools.get(index).name();
            if (canonicalToWire.containsKey(canonical)) throw new IllegalArgumentException("Duplicate tool name");
            String wire = "mcp_" + index + "_" + canonical.replace('.', '_');
            if (wire.length() > 64) wire = wire.substring(0, 64);
            canonicalToWire.put(canonical, wire);
            wireToCanonical.put(wire, canonical);
        }
        return new ToolAliases(Map.copyOf(canonicalToWire), Map.copyOf(wireToCanonical));
    }

    private static Map<String, Object> wireMessage(AgentLoop.Message message, Map<String, String> aliases) {
        if ("assistant".equals(message.role()) && !message.toolCalls().isEmpty()) {
            return Map.of("role", "assistant", "content", "", "tool_calls", message.toolCalls().stream()
                    .map(call -> Map.of("id", call.id(), "type", "function", "function", Map.of(
                            "name", requiredAlias(call.name(), aliases),
                            "arguments", writeArguments(call.arguments())))).toList());
        }
        if ("tool".equals(message.role()) && !message.toolCalls().isEmpty()) {
            return Map.of("role", "tool", "tool_call_id", message.toolCalls().getFirst().id(),
                    "content", message.content());
        }
        return Map.of("role", message.role(), "content", message.content());
    }

    private static String writeArguments(Map<String, Object> arguments) {
        try { return new ObjectMapper().writeValueAsString(arguments); }
        catch (Exception exception) { throw new IllegalStateException("Cannot serialize tool arguments", exception); }
    }
    private static String requiredAlias(String canonical, Map<String, String> aliases) {
        String alias = aliases.get(canonical);
        if (alias == null) throw failure("unknown_tool", false, "Conversation uses an undeclared tool");
        return alias;
    }
    private static String boundedDescription(String description) {
        String clean = description.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").strip();
        if (clean.length() > 300) clean = clean.substring(0, 300);
        return "UNTRUSTED TOOL METADATA: " + clean;
    }
    private static void authorize(HttpHeaders headers, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) headers.setBearerAuth(apiKey);
    }
    private static LlmCompletionException failure(String reason, boolean retryable, String message) {
        return new LlmCompletionException(reason, retryable, message);
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException
                    || current instanceof java.net.http.HttpTimeoutException) return true;
        }
        return false;
    }

    record ToolAliases(Map<String, String> canonicalToWire, Map<String, String> wireToCanonical) {}
}
