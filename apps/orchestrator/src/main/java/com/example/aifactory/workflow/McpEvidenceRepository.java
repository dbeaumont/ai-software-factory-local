package com.example.aifactory.workflow;

import com.example.aifactory.config.McpClientProperties;
import com.example.aifactory.service.McpToolInvoker;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.MessageDigest;
import java.util.HexFormat;

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
        if (!server.enabled()) throw new IllegalStateException("evidence MCP server is disabled");
        if (request == null || !"workflow".equals(request.actor())) {
            throw new SecurityException("only the workflow may create an evidence manifest");
        }
        Map<String, Object> artifacts = new LinkedHashMap<>();
        request.artifacts().forEach((name, reference) -> artifacts.put(name, Map.of(
                "uri", reference.uri(), "digest", reference.digest(), "status", reference.status())));
        PolicyDecision decision = request.policyDecision();
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("schema_version", decision.schemaVersion());
        policy.put("task_id", decision.taskId());
        policy.put("attempt_id", decision.attemptId());
        policy.put("policy_id", decision.policyId());
        policy.put("policy_version", decision.policyVersion());
        policy.put("decision", decision.decision());
        policy.put("reasons", decision.reasons());
        policy.put("input_digests", decision.inputDigests());
        policy.put("decided_at", decision.decidedAt().toString());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schema_version", "1");
        arguments.put("task_id", request.taskId());
        arguments.put("attempt_id", request.attemptId());
        arguments.put("repository_id", request.repositoryId());
        arguments.put("source_commit", request.sourceCommit());
        arguments.put("patch_digest", request.patchDigest());
        arguments.put("artifacts", Map.copyOf(artifacts));
        arguments.put("policy_decision", Map.copyOf(policy));
        arguments.put("actor", request.actor());
        JsonNode response = mcp.call(server.expectedName(), "evidence.create_manifest", Map.copyOf(arguments));
        StoredManifest manifest = new StoredManifest(requiredText(response, "manifest_id"),
                requiredText(response, "uri"), requiredText(response, "digest"), requiredText(response, "status"),
                requiredText(response, "classification"), Instant.parse(requiredText(response, "retain_until")),
                Instant.parse(requiredText(response, "created_at")));
        String expectedUri = "evidence://" + request.taskId() + '/' + request.attemptId()
                + "/manifest/" + manifest.manifestId();
        if (!expectedUri.equals(manifest.uri()) || !"COMPLETE".equals(manifest.status())
                || !manifest.manifestId().matches("[0-9a-f]{64}")
                || !manifest.digest().matches("[0-9a-f]{64}")) {
            throw new SecurityException("evidence MCP returned an invalid manifest binding");
        }
        return manifest;
    }

    @Override
    public EvidenceSummary getSummary(String taskId, String uri, String actor) {
        throw new UnsupportedOperationException("summary migration is pending");
    }

    @Override
    public RawEvidence read(ReadRequest request) {
        if (!server.enabled()) throw new IllegalStateException("evidence MCP server is disabled");
        if (request == null || !("workflow".equals(request.actor()) || "reviewer".equals(request.actor()))
                || request.purpose() == null || request.purpose().isBlank()) {
            throw new SecurityException("raw evidence read is not authorized");
        }
        JsonNode response = mcp.call(server.expectedName(), "evidence.read", Map.of(
                "schema_version", "1", "task_id", request.taskId(), "uri", request.uri(),
                "actor", request.actor(), "purpose", request.purpose()));
        byte[] content;
        try {
            content = Base64.getDecoder().decode(requiredText(response, "content_base64"));
        } catch (IllegalArgumentException invalidBase64) {
            throw new SecurityException("evidence MCP returned invalid content encoding", invalidBase64);
        }
        String digest = requiredText(response, "digest");
        String actualDigest = sha256(content);
        RawEvidence evidence = new RawEvidence(requiredText(response, "uri"), requiredText(response, "type"),
                digest, requiredText(response, "status"), requiredText(response, "classification"), content);
        if (!request.uri().equals(evidence.uri()) || !evidence.uri().startsWith("evidence://" + request.taskId() + '/')
                || !MessageDigest.isEqual(HexFormat.of().parseHex(digest), HexFormat.of().parseHex(actualDigest))
                || !"COMPLETE".equals(evidence.status())) {
            throw new SecurityException("raw evidence response failed task, URI, digest or status binding");
        }
        return evidence;
    }

    private static String requiredText(JsonNode response, String field) {
        String value = response == null ? null : response.path(field).asText(null);
        if (value == null || value.isBlank()) throw new SecurityException("evidence MCP response misses " + field);
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
