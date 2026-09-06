package com.example.aifactory.agentruntime;

import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated SUT behaviour required by the upstream A2A TCK.
 *
 * <p>This bean is absent from normal runtimes and can only be enabled in the
 * ephemeral, network-isolated conformance container created by the TCK script.
 */
@Service
@ConditionalOnProperty(name = "ai-factory.agent-runtime.tck.enabled", havingValue = "true")
final class A2aTckConformanceService {
    private final ObjectMapper mapper;
    private final Map<String, ConformanceTask> tasks = new ConcurrentHashMap<>();

    A2aTckConformanceService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    synchronized Map<String, Object> handle(String method, JsonNode params) {
        return switch (method) {
            case "SendMessage" -> send(params);
            case "GetTask" -> task(requireTask(text(params, "id")), historyLength(params), true);
            case "ListTasks" -> list(params);
            case "CancelTask" -> cancel(params);
            case "SendStreamingMessage", "SubscribeToTask", "GetExtendedAgentCard" ->
                    throw new Failure(A2AErrorCodes.UNSUPPORTED_OPERATION, "A2A operation is not supported");
            case "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig",
                 "ListTaskPushNotificationConfigs", "DeleteTaskPushNotificationConfig" ->
                    throw new Failure(A2AErrorCodes.PUSH_NOTIFICATION_NOT_SUPPORTED,
                            "Push notifications are not supported");
            default -> throw new Failure(A2AErrorCodes.METHOD_NOT_FOUND, "A2A method is not available");
        };
    }

    private Map<String, Object> send(JsonNode params) {
        JsonNode message = params.path("message");
        if (!message.isObject() || !message.path("parts").isArray() || message.path("parts").isEmpty()) {
            throw new Failure(A2AErrorCodes.INVALID_PARAMS, "A valid A2A message is required");
        }
        for (JsonNode part : message.path("parts")) {
            if (part.has("raw") && !"application/octet-stream".equals(part.path("mediaType").asText())) {
                throw new Failure(A2AErrorCodes.CONTENT_TYPE_NOT_SUPPORTED,
                        "The requested content type is not supported");
            }
        }
        String messageId = text(message, "messageId");
        String requestedContextId = optionalText(message, "contextId");
        if (requestedContextId != null && requestedContextId.contains("client-context-rejected")) {
            throw new Failure(A2AErrorCodes.INVALID_PARAMS, "The client contextId is not accepted");
        }
        String requestedTaskId = optionalText(message, "taskId");
        ConformanceTask current = requestedTaskId == null ? null : requireTask(requestedTaskId);
        if (current != null && terminal(current.state)) {
            throw new Failure(A2AErrorCodes.UNSUPPORTED_OPERATION, "A terminal task cannot accept messages");
        }
        String state = messageId.contains("input-required")
                ? "TASK_STATE_INPUT_REQUIRED" : "TASK_STATE_COMPLETED";
        Instant now = Instant.now();
        if (messageId.contains("message-response")) {
            return Map.of("message", Map.of(
                    "role", "ROLE_AGENT",
                    "messageId", UUID.randomUUID().toString(),
                    "contextId", UUID.randomUUID().toString(),
                    "parts", List.of(Map.of("text", "Direct message response"))));
        }
        Map<String, Object> normalizedMessage = mapper.convertValue(message, Map.class);
        if (current == null) {
            current = new ConformanceTask(UUID.randomUUID().toString(),
                    requestedContextId == null ? UUID.randomUUID().toString() : requestedContextId,
                    state, now, new ArrayList<>(), artifacts(messageId));
            tasks.put(current.id, current);
        }
        current.history.add(normalizedMessage);
        current.state = state;
        current.updatedAt = now;
        return Map.of("task", task(current, Integer.MAX_VALUE, true));
    }

    private static List<Map<String, Object>> artifacts(String messageId) {
        Map<String, Object> part;
        if (messageId.contains("artifact-file-url")) {
            part = Map.of("url", "https://example.invalid/output.txt", "filename", "output.txt",
                    "mediaType", "text/plain");
        } else if (messageId.contains("artifact-file")) {
            part = Map.of("raw", "R2VuZXJhdGVkIGZpbGUgY29udGVudA==", "filename", "output.txt",
                    "mediaType", "text/plain");
        } else if (messageId.contains("artifact-data")) {
            part = Map.of("data", Map.of("key", "value", "count", 42));
        } else if (messageId.contains("artifact-text")) {
            part = Map.of("text", "Generated text content");
        } else {
            return List.of();
        }
        return List.of(Map.of("artifactId", UUID.randomUUID().toString(), "parts", List.of(part)));
    }

    private Map<String, Object> cancel(JsonNode params) {
        ConformanceTask task = requireTask(text(params, "id"));
        if (terminal(task.state)) {
            throw new Failure(A2AErrorCodes.TASK_NOT_CANCELABLE, "Task is not cancelable");
        }
        task.state = "TASK_STATE_CANCELED";
        task.updatedAt = Instant.now();
        return task(task, Integer.MAX_VALUE, true);
    }

    private Map<String, Object> list(JsonNode params) {
        String contextId = optionalText(params, "context_id");
        if (contextId == null) contextId = optionalText(params, "contextId");
        int pageSize = integer(params, "page_size", "pageSize", 50);
        boolean includeArtifacts = bool(params, "include_artifacts", "includeArtifacts", false);
        final String filter = contextId;
        List<Map<String, Object>> visible = tasks.values().stream()
                .filter(task -> filter == null || filter.equals(task.contextId))
                .sorted(Comparator.comparing((ConformanceTask task) -> task.updatedAt).reversed())
                .limit(Math.max(1, pageSize))
                .map(task -> task(task, historyLength(params), includeArtifacts))
                .toList();
        return Map.of("tasks", visible, "nextPageToken", "");
    }

    private ConformanceTask requireTask(String id) {
        ConformanceTask task = tasks.get(id);
        if (task == null) throw new Failure(A2AErrorCodes.TASK_NOT_FOUND, "Task not found");
        return task;
    }

    private static Map<String, Object> task(ConformanceTask source, int historyLength, boolean includeArtifacts) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", source.id);
        result.put("contextId", source.contextId);
        result.put("status", Map.of("state", source.state, "timestamp", source.updatedAt.toString()));
        if (includeArtifacts) result.put("artifacts", source.artifacts);
        int from = Math.max(0, source.history.size() - Math.max(0, historyLength));
        result.put("history", List.copyOf(source.history.subList(from, source.history.size())));
        return Map.copyOf(result);
    }

    private static int historyLength(JsonNode params) {
        return integer(params, "history_length", "historyLength", 0);
    }

    private static int integer(JsonNode node, String snake, String camel, int fallback) {
        if (node.has(snake)) return node.path(snake).asInt(fallback);
        return node.path(camel).asInt(fallback);
    }

    private static boolean bool(JsonNode node, String snake, String camel, boolean fallback) {
        if (node.has(snake)) return node.path(snake).asBoolean(fallback);
        return node.path(camel).asBoolean(fallback);
    }

    private static String text(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) throw new Failure(A2AErrorCodes.INVALID_PARAMS, field + " is required");
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean terminal(String state) {
        return state.equals("TASK_STATE_COMPLETED") || state.equals("TASK_STATE_CANCELED")
                || state.equals("TASK_STATE_REJECTED") || state.equals("TASK_STATE_FAILED");
    }

    static final class Failure extends RuntimeException {
        final A2AErrorCodes code;

        Failure(A2AErrorCodes code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class ConformanceTask {
        private final String id;
        private final String contextId;
        private String state;
        private Instant updatedAt;
        private final List<Map<String, Object>> history;
        private final List<Map<String, Object>> artifacts;

        private ConformanceTask(String id, String contextId, String state, Instant updatedAt,
                                List<Map<String, Object>> history, List<Map<String, Object>> artifacts) {
            this.id = id;
            this.contextId = contextId;
            this.state = state;
            this.updatedAt = updatedAt;
            this.history = history;
            this.artifacts = artifacts;
        }
    }
}
