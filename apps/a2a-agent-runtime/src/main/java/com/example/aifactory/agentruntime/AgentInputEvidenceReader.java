package com.example.aifactory.agentruntime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** Resolves the immutable input selected by the A2A envelope and verifies its complete binding. */
final class AgentInputEvidenceReader {
    private final String role;
    private final URI evidenceUrl;
    private final Duration timeout;
    private final RoleScopedMcpClient.SessionFactory sessions;
    private final ObjectMapper mapper;

    AgentInputEvidenceReader(String role, AgentMcpProperties properties,
                             RoleScopedMcpClient.SessionFactory sessions, ObjectMapper mapper) {
        this.role = role;
        this.evidenceUrl = properties.evidenceUrl();
        this.timeout = properties.requestTimeout();
        this.sessions = sessions;
        this.mapper = mapper;
    }

    JsonNode read(String taskId, String attemptId, Reference reference, long maximumInputBytes) {
        JsonNode response;
        try (RoleScopedMcpClient.Session session = sessions.connect("evidence-mcp", evidenceUrl, timeout)) {
            if (!session.tools().contains("evidence.read")) {
                throw new IllegalStateException("Evidence MCP does not advertise evidence.read");
            }
            response = mapper.readTree(session.call("evidence.read", Map.of(
                    "schema_version", "1", "task_id", taskId, "attempt_id", attemptId,
                    "uri", reference.uri(), "actor", role, "purpose", "agent-execution-input")));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("A2A input Evidence read failed", failure);
        }
        String encoded = required(response, "content_base64");
        byte[] content;
        try {
            content = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException malformed) {
            throw new SecurityException("A2A input Evidence content is not base64", malformed);
        }
        if (!reference.uri().equals(required(response, "uri"))
                || !reference.digest().equals(required(response, "digest"))
                || !"COMPLETE".equals(required(response, "status"))
                || response.path("size_bytes").asLong(-1) != reference.sizeBytes()
                || content.length != reference.sizeBytes()
                || content.length > maximumInputBytes
                || !reference.digest().equals(Digests.sha256(content))) {
            throw new SecurityException("A2A input Evidence reference is not bound to its content");
        }
        try {
            return mapper.readTree(content);
        } catch (Exception malformed) {
            if ("integration-result-v1".equals(reference.contract())) {
                return mapper.getNodeFactory().textNode(new String(content, StandardCharsets.UTF_8));
            }
            throw new IllegalArgumentException("A2A input Evidence is not valid JSON", malformed);
        }
    }

    private static String required(JsonNode value, String field) {
        String text = value.path(field).asText("");
        if (text.isBlank()) throw new IllegalStateException("Evidence MCP response lacks " + field);
        return text;
    }

    record Reference(String referenceId, String uri, String digest, long sizeBytes, String contract) {}
}
