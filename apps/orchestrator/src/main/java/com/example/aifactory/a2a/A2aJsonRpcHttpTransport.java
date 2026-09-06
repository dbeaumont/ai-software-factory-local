package com.example.aifactory.a2a;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Production JSON-RPC 2.0 transport for the application-level A2A client port. */
public final class A2aJsonRpcHttpTransport implements A2aAuthenticatedClientTransport {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final Function<String, URI> endpoints;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public A2aJsonRpcHttpTransport(AllowListedAgentRegistry registry, HttpClient http, ObjectMapper mapper) {
        this(role -> registry.require(role).endpoint(), http, mapper);
    }

    A2aJsonRpcHttpTransport(Function<String, URI> endpoints, HttpClient http, ObjectMapper mapper) {
        this.endpoints = endpoints;
        this.http = http;
        this.mapper = mapper;
    }

    public static A2aJsonRpcHttpTransport forEndpoint(URI endpoint, HttpClient http, ObjectMapper mapper) {
        return new A2aJsonRpcHttpTransport(ignored -> endpoint, http, mapper);
    }

    @Override
    public CompletionStage<A2aContracts.TaskSnapshot> send(A2aContracts.SendCommand command, char[] bearerToken) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", command.messageId());
        if (command.taskId() != null) message.put("taskId", command.taskId());
        if (command.contextId() != null) message.put("contextId", command.contextId());
        message.put("parts", command.parts().stream().map(A2aJsonRpcHttpTransport::part).toList());
        message.put("metadata", command.metadata());
        Map<String, Object> params = Map.of(
                "message", Map.copyOf(message),
                "configuration", Map.of("returnImmediately", command.returnImmediately()));
        return invoke(command.agentRole(), "SendMessage", params, bearerToken).thenApply(result -> {
            JsonNode task = result.has("task") ? result.path("task") : result;
            return task(task);
        });
    }

    @Override
    public CompletionStage<A2aContracts.TaskSnapshot> getTask(A2aContracts.TaskQuery query, char[] bearerToken) {
        return invoke(query.agentRole(), "GetTask",
                Map.of("id", query.taskId(), "historyLength", query.historyLength()), bearerToken)
                .thenApply(this::task);
    }

    @Override
    public CompletionStage<A2aContracts.TaskSnapshot> cancelTask(A2aContracts.TaskQuery query, char[] bearerToken) {
        return invoke(query.agentRole(), "CancelTask", Map.of("id", query.taskId()), bearerToken)
                .thenApply(this::task);
    }

    @Override
    public CompletionStage<Optional<A2aContracts.TaskSnapshot>> findTaskByMessageId(
            String agentRole, String messageId, char[] bearerToken) {
        return invoke(agentRole, "ListTasks", Map.of("pageSize", 100, "includeArtifacts", true), bearerToken)
                .thenApply(result -> {
                    for (JsonNode candidate : result.path("tasks")) {
                        if (messageId.equals(candidate.path("metadata").path("messageId").asText())) {
                            return Optional.of(task(candidate));
                        }
                    }
                    return Optional.empty();
                });
    }

    private CompletionStage<JsonNode> invoke(String role, String method, Map<String, Object> params,
                                              char[] bearerToken) {
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "jsonrpc", "2.0", "id", UUID.randomUUID().toString(),
                    "method", method, "params", params));
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoints.apply(role))
                    .timeout(TIMEOUT)
                    .header("A2A-Version", "1.0")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            if (bearerToken != null && bearerToken.length > 0) {
                request.header("Authorization", "Bearer " + new String(bearerToken));
            }
            return http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                            HttpResponse.BodyHandlers.ofByteArray())
                    .thenApply(response -> decode(response.statusCode(), response.body()));
        } catch (Exception failure) {
            return CompletableFuture.failedStage(new A2aTransportException("Cannot encode A2A request", failure));
        }
    }

    private JsonNode decode(int status, byte[] body) {
        try {
            JsonNode envelope = mapper.readTree(body);
            if (status < 200 || status >= 300 || envelope.has("error")) {
                JsonNode error = envelope.path("error");
                throw new A2aTransportException("A2A request failed: " + error.path("code").asInt()
                        + " " + error.path("message").asText());
            }
            JsonNode result = envelope.path("result");
            if (result.isMissingNode() || result.isNull()) {
                throw new A2aTransportException("A2A response has no result");
            }
            return result;
        } catch (A2aTransportException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new A2aTransportException("Cannot decode A2A response", failure);
        }
    }

    private static Map<String, Object> part(A2aContracts.Part part) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("mediaType", part.mediaType());
        if (part.text() != null) wire.put("text", part.text());
        if (!part.data().isEmpty()) wire.put("data", part.data());
        if (part.uri() != null) wire.put("url", part.uri().toString());
        return Map.copyOf(wire);
    }

    private A2aContracts.TaskSnapshot task(JsonNode node) {
        if (!node.isObject() || node.path("id").asText().isBlank()) {
            throw new A2aTransportException("A2A result is not a task");
        }
        JsonNode status = node.path("status");
        String stateName = status.path("state").asText("TASK_STATE_UNKNOWN")
                .replaceFirst("^TASK_STATE_", "");
        A2aContracts.TaskState state;
        try {
            state = A2aContracts.TaskState.valueOf(stateName);
        } catch (IllegalArgumentException unknown) {
            state = A2aContracts.TaskState.UNKNOWN;
        }
        Instant timestamp;
        try {
            timestamp = Instant.parse(status.path("timestamp").asText());
        } catch (Exception absent) {
            timestamp = Instant.EPOCH;
        }
        List<A2aContracts.Artifact> artifacts = new ArrayList<>();
        for (JsonNode artifact : node.path("artifacts")) {
            List<A2aContracts.Part> parts = new ArrayList<>();
            for (JsonNode value : artifact.path("parts")) {
                if (value.has("text")) {
                    parts.add(new A2aContracts.Part(A2aMediaTypes.TEXT, value.path("text").asText(), Map.of(), null));
                } else if (value.has("data")) {
                    parts.add(new A2aContracts.Part(A2aMediaTypes.JSON, null,
                            mapper.convertValue(value.path("data"), new TypeReference<>() {}), null));
                }
            }
            if (!parts.isEmpty()) {
                String artifactId = artifact.path("artifactId").asText(UUID.randomUUID().toString());
                artifacts.add(new A2aContracts.Artifact(artifactId,
                        artifact.path("name").asText(artifactId), parts, Map.of()));
            }
        }
        Map<String, Object> metadata = node.path("metadata").isObject()
                ? mapper.convertValue(node.path("metadata"), new TypeReference<>() {}) : Map.of();
        return new A2aContracts.TaskSnapshot(node.path("id").asText(),
                node.path("contextId").asText(null), state, timestamp, artifacts, metadata);
    }

    public static final class A2aTransportException extends RuntimeException {
        A2aTransportException(String message) { super(message); }
        A2aTransportException(String message, Throwable cause) { super(message, cause); }
    }
}
