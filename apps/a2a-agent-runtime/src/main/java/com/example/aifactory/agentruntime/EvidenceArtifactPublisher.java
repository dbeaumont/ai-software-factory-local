package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentContractValidator;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import com.example.aifactory.agentcore.EvidenceUriPolicy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validates output, stores immutable content in Evidence MCP, then publishes only its A2A reference. */
public final class EvidenceArtifactPublisher {
    private final RoleScopedAgentContext role;
    private final A2aTaskStore store;
    private final AgentMcpProperties properties;
    private final RoleScopedMcpClient.SessionFactory sessions;
    private final ObjectMapper mapper;

    EvidenceArtifactPublisher(RoleScopedAgentContext role, A2aTaskStore store, AgentMcpProperties properties,
                              RoleScopedMcpClient.SessionFactory sessions, ObjectMapper mapper) {
        this.role = role;
        this.store = store;
        this.properties = properties;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public AgentArtifactActivities.ArtifactReference publish(AgentArtifactActivities.PublishCommand command) {
        validateCommand(command);
        byte[] content;
        JsonNode document;
        try {
            content = Base64.getDecoder().decode(command.contentBase64());
            if (!digest(content).equals(command.digest())) throw new SecurityException("Agent artifact digest mismatch");
            document = mapper.readTree(content);
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Agent artifact must be valid base64 JSON", exception);
        }
        role.requireActiveRole(command.role());
        role.validateOutput(command.outputContract(), document, new AgentContractValidator.Context(
                command.taskId(), command.attemptId(), command.allowedReferenceIds()));
        Map<String, Object> request = Map.of(
                "schema_version", "1", "task_id", command.taskId(), "attempt_id", command.attemptId(),
                "type", "agent-result", "media_type", "application/json",
                "content_base64", command.contentBase64(), "digest", command.digest(), "actor", command.role());
        JsonNode stored;
        try (RoleScopedMcpClient.Session session = sessions.connect(
                "evidence-mcp", properties.evidenceUrl(), properties.requestTimeout())) {
            if (!session.tools().contains("evidence.store")) {
                throw new IllegalStateException("Evidence MCP does not advertise evidence.store");
            }
            stored = mapper.readTree(session.call("evidence.store", request));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Evidence MCP publication failed", exception);
        }
        String uri = required(stored, "uri");
        String returnedDigest = required(stored, "digest");
        String status = required(stored, "status");
        if (!command.digest().equals(returnedDigest) || !"COMPLETE".equals(status)
                || !uri.equals("evidence://" + command.taskId() + '/' + command.attemptId()
                + "/agent-result/" + command.digest())) {
            throw new SecurityException("Evidence MCP returned an unbound artifact reference");
        }
        EvidenceUriPolicy.requireBound(uri, command.taskId(), command.attemptId(), returnedDigest);
        String artifactId = command.taskId() + ":result:" + command.digest();
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("schema_version", "1");
        reference.put("reference_id", artifactId);
        reference.put("uri", uri);
        reference.put("digest", returnedDigest);
        reference.put("size_bytes", content.length);
        reference.put("media_type", "application/json");
        reference.put("classification", required(stored, "classification"));
        reference.put("contract", command.outputContract());
        reference.put("contract_version", command.outputContract().replaceFirst("^.*-v", ""));
        Map<String, Object> artifact = Map.of(
                "artifactId", artifactId,
                "name", command.role() + "-result",
                "parts", java.util.List.of(Map.of("kind", "data", "data", Map.copyOf(reference))));
        A2aTaskStore.StoredTask task = store.find(command.taskId())
                .orElseThrow(() -> new IllegalStateException("A2A task is absent while publishing its artifact"));
        store.putArtifact(new A2aTaskStore.ArtifactRecord(
                artifactId, task.taskId(), task.tenantId(), task.callerSubject(), returnedDigest, artifact));
        return new AgentArtifactActivities.ArtifactReference(artifactId, uri, returnedDigest);
    }

    private static void validateCommand(AgentArtifactActivities.PublishCommand command) {
        if (command == null || command.taskId() == null || command.taskId().isBlank()
                || command.attemptId() == null || command.attemptId().isBlank()
                || command.role() == null || command.role().isBlank()
                || command.outputContract() == null || !command.outputContract().matches("[a-z][a-z0-9-]*-v[1-9][0-9]*")
                || command.contentBase64() == null || command.digest() == null
                || !command.digest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Agent artifact publication command is incomplete");
        }
    }

    private static String required(JsonNode value, String field) {
        String text = value.path(field).asText("");
        if (text.isBlank()) throw new IllegalStateException("Evidence MCP response lacks " + field);
        return text;
    }

    private static String digest(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
