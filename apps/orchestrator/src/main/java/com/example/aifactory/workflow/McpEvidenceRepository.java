package com.example.aifactory.workflow;

import com.example.aifactory.config.McpClientProperties;
import com.example.aifactory.service.McpToolInvoker;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** EvidenceRepository adapter whose writes are exclusively mediated by Evidence MCP. */
@Service
public final class McpEvidenceRepository implements EvidenceRepository {
    private final McpToolInvoker mcp;
    private final McpClientProperties.Server server;

    public McpEvidenceRepository(McpToolInvoker mcp, McpClientProperties properties) {
        this.mcp = mcp;
        this.server = properties.servers().get("evidence");
        if (server == null) throw new IllegalStateException("evidence MCP server is not configured");
    }

    @Override
    public StoredEvidence store(StoreRequest request) {
        if (!server.enabled()) throw new IllegalStateException("evidence MCP server is disabled");
        if (request == null || request.content() == null) {
            throw new IllegalArgumentException("evidence store request and content are required");
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schema_version", "1");
        arguments.put("task_id", request.taskId());
        arguments.put("attempt_id", request.attemptId());
        arguments.put("type", request.type());
        arguments.put("media_type", request.mediaType());
        arguments.put("content_base64", Base64.getEncoder().encodeToString(request.content()));
        arguments.put("digest", request.digest());
        arguments.put("actor", request.actor());
        JsonNode response = mcp.call(server.expectedName(), "evidence.store", Map.copyOf(arguments));
        StoredEvidence stored = new StoredEvidence(requiredText(response, "uri"), requiredText(response, "digest"),
                requiredText(response, "status"), requiredText(response, "media_type"),
                response.path("size_bytes").asLong(-1), requiredText(response, "classification"),
                Instant.parse(requiredText(response, "retain_until")),
                Instant.parse(requiredText(response, "stored_at")));
        String expectedUri = "evidence://" + request.taskId() + '/' + request.attemptId() + '/'
                + request.type() + '/' + request.digest();
        if (!expectedUri.equals(stored.uri()) || !request.digest().equals(stored.digest())
                || !"COMPLETE".equals(stored.status()) || stored.sizeBytes() != request.content().length) {
            throw new SecurityException("evidence MCP returned metadata outside the submitted artifact binding");
        }
        return stored;
    }

    @Override
    public StoredManifest createManifest(ManifestRequest request) {
        throw new UnsupportedOperationException("manifest migration is pending");
    }

    @Override
    public EvidenceSummary getSummary(String taskId, String uri, String actor) {
        throw new UnsupportedOperationException("summary migration is pending");
    }

    @Override
    public RawEvidence read(ReadRequest request) {
        throw new UnsupportedOperationException("raw evidence migration is pending");
    }

    private static String requiredText(JsonNode response, String field) {
        String value = response == null ? null : response.path(field).asText(null);
        if (value == null || value.isBlank()) throw new SecurityException("evidence MCP response misses " + field);
        return value;
    }
}
