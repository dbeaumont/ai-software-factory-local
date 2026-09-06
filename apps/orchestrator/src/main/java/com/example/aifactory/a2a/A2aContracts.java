package com.example.aifactory.a2a;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Transport-neutral contracts used at the application boundary.
 *
 * <p>SDK-specific A2A types must be converted by adapters and must never escape into these records.</p>
 */
public final class A2aContracts {

    private A2aContracts() {
    }

    public record Part(String mediaType, String text, Map<String, Object> data, URI uri) {
        public Part {
            mediaType = requireText(mediaType, "mediaType");
            data = immutableMap(data);
            if (text == null && data.isEmpty() && uri == null) {
                throw new IllegalArgumentException("An A2A part requires text, data or uri");
            }
        }
    }

    public record SendCommand(
            String agentRole,
            String skillId,
            String messageId,
            String taskId,
            String contextId,
            List<Part> parts,
            Map<String, Object> metadata,
            boolean returnImmediately) {
        public SendCommand {
            agentRole = requireText(agentRole, "agentRole");
            skillId = requireText(skillId, "skillId");
            messageId = requireText(messageId, "messageId");
            taskId = optionalText(taskId, "taskId");
            contextId = optionalText(contextId, "contextId");
            parts = immutableList(parts, "parts");
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("parts must not be empty");
            }
            metadata = immutableMap(metadata);
            if (!returnImmediately) {
                throw new IllegalArgumentException("A2A sends must use the asynchronous returnImmediately profile");
            }
        }
    }

    public record TaskQuery(String agentRole, String taskId, int historyLength) {
        public TaskQuery {
            agentRole = requireText(agentRole, "agentRole");
            taskId = requireText(taskId, "taskId");
            if (historyLength < 0) {
                throw new IllegalArgumentException("historyLength must be positive or zero");
            }
        }
    }

    public record TaskListQuery(
            String agentRole,
            String contextId,
            TaskState state,
            int pageSize,
            String pageToken) {
        public TaskListQuery {
            agentRole = requireText(agentRole, "agentRole");
            contextId = optionalText(contextId, "contextId");
            pageToken = optionalText(pageToken, "pageToken");
            if (pageSize < 1) {
                throw new IllegalArgumentException("pageSize must be greater than zero");
            }
        }
    }

    public record Artifact(
            String artifactId,
            String name,
            List<Part> parts,
            Map<String, Object> metadata) {
        public Artifact {
            artifactId = requireText(artifactId, "artifactId");
            name = requireText(name, "name");
            parts = immutableList(parts, "parts");
            metadata = immutableMap(metadata);
        }
    }

    public record TaskSnapshot(
            String taskId,
            String contextId,
            TaskState state,
            Instant updatedAt,
            List<Artifact> artifacts,
            Map<String, Object> metadata) {
        public TaskSnapshot {
            taskId = requireText(taskId, "taskId");
            contextId = optionalText(contextId, "contextId");
            state = Objects.requireNonNull(state, "state");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            artifacts = immutableList(artifacts, "artifacts");
            metadata = immutableMap(metadata);
        }
    }

    public record TaskPage(List<TaskSnapshot> tasks, String nextPageToken) {
        public TaskPage {
            tasks = immutableList(tasks, "tasks");
            nextPageToken = optionalText(nextPageToken, "nextPageToken");
        }
    }

    public record AgentCardDescriptor(
            String agentRole,
            URI cardUri,
            URI endpoint,
            String protocolBinding,
            String protocolVersion,
            String cardDigest,
            List<String> skillIds,
            boolean streaming,
            boolean pushNotifications) {
        public AgentCardDescriptor {
            agentRole = requireText(agentRole, "agentRole");
            cardUri = Objects.requireNonNull(cardUri, "cardUri");
            endpoint = Objects.requireNonNull(endpoint, "endpoint");
            protocolBinding = requireText(protocolBinding, "protocolBinding");
            protocolVersion = requireText(protocolVersion, "protocolVersion");
            cardDigest = requireText(cardDigest, "cardDigest");
            skillIds = immutableList(skillIds, "skillIds");
            if (streaming) {
                throw new IllegalArgumentException("streaming is not enabled for the initial A2A release");
            }
        }
    }

    public record Notification(
            String agentRole,
            String taskId,
            String contextId,
            long sequence,
            TaskState state,
            Instant occurredAt,
            List<Artifact> artifacts,
            Map<String, Object> metadata) {
        public Notification {
            agentRole = requireText(agentRole, "agentRole");
            taskId = requireText(taskId, "taskId");
            contextId = optionalText(contextId, "contextId");
            if (sequence < 0) {
                throw new IllegalArgumentException("sequence must be positive or zero");
            }
            state = Objects.requireNonNull(state, "state");
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            artifacts = immutableList(artifacts, "artifacts");
            metadata = immutableMap(metadata);
        }
    }

    public enum TaskState {
        SUBMITTED,
        WORKING,
        INPUT_REQUIRED,
        AUTH_REQUIRED,
        COMPLETED,
        REJECTED,
        FAILED,
        CANCELED,
        UNKNOWN
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " must be null or non-blank");
        }
        return value;
    }

    private static <T> List<T> immutableList(List<T> value, String field) {
        Objects.requireNonNull(value, field);
        if (value.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + " must not contain null values");
        }
        return List.copyOf(value);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        Objects.requireNonNull(value, "map");
        if (value.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new IllegalArgumentException("maps must not contain null keys or values");
        }
        return Map.copyOf(value);
    }
}
