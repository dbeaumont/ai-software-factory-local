package com.example.aifactory.service;

import com.example.aifactory.config.ScmDeliveryClientProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ScmDeliveryGateway {
    private static final Map<String, String> EVIDENCE_PATHS = Map.of(
            "plan", ".ai-plan.md", "tests", ".ai-factory/test.txt", "quality", ".ai-factory/sonar.txt",
            "sbom", ".ai-factory/sbom.cdx.json", "security", ".ai-factory/trivy.txt", "review", ".ai-review.md");
    private final McpToolInvoker mcp;
    private final McpResponseValidator validator;
    private final ScmDeliveryClientProperties properties;

    public ScmDeliveryGateway(McpToolInvoker mcp, McpResponseValidator validator,
                              ScmDeliveryClientProperties properties) {
        this.mcp = mcp;
        this.validator = validator;
        this.properties = properties;
    }

    public String createDraftPullRequest(Path workspace, String repositoryUrl, String baseBranch,
                                         String taskId, String sourceCommit, String title) throws Exception {
        if (!properties.enabled()) {
            throw new IllegalStateException("SCM delivery MCP is disabled");
        }
        String repositoryId = repositoryId(repositoryUrl);
        String attemptId = "approval-1";
        String patchDigest = digest(workspace.resolve("changes.patch"));
        Map<String, String> evidence = new LinkedHashMap<>();
        EVIDENCE_PATHS.forEach((name, relative) -> evidence.put(name, digest(workspace.resolve(relative))));
        Instant approvedAt = Instant.now();
        Instant expiresAt = approvedAt.plusSeconds(3600);
        Map<String, Object> proof = approvalProof(taskId, attemptId, repositoryId, sourceCommit, patchDigest,
                approvedAt, expiresAt);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schema_version", "1");
        arguments.put("task_id", taskId);
        arguments.put("attempt_id", attemptId);
        arguments.put("repository_id", repositoryId);
        arguments.put("source_commit", sourceCommit);
        arguments.put("patch_digest", patchDigest);
        arguments.put("evidence_digests", evidence);
        arguments.put("base_branch", baseBranch);
        arguments.put("title", title);
        arguments.put("actor", "delivery");
        arguments.put("idempotency_key", "delivery-" + taskId + '-' + attemptId);
        arguments.put("approval_proof", proof);
        JsonNode result = validator.validate("scm.create_draft_pull_request",
                mcp.call(properties.serverName(), "scm.create_draft_pull_request", arguments));
        if (!result.path("draft").asBoolean(false)) {
            throw new IllegalStateException("SCM delivery did not create a draft pull request");
        }
        return result.path("pullRequestUrl").asText();
    }

    private Map<String, Object> approvalProof(String taskId, String attemptId, String repositoryId,
                                               String sourceCommit, String patchDigest,
                                               Instant approvedAt, Instant expiresAt) throws Exception {
        String canonical = String.join("\n", "1", taskId, attemptId, repositoryId, sourceCommit, patchDigest,
                "APPROVED", properties.approver(), approvedAt.toString(), expiresAt.toString());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.approvalKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("schema_version", "1");
        proof.put("task_id", taskId);
        proof.put("attempt_id", attemptId);
        proof.put("repository_id", repositoryId);
        proof.put("source_sha", sourceCommit);
        proof.put("patch_digest", patchDigest);
        proof.put("decision", "APPROVED");
        proof.put("approver", properties.approver());
        proof.put("approved_at", approvedAt.toString());
        proof.put("expires_at", expiresAt.toString());
        proof.put("signature", signature);
        return proof;
    }

    static String repositoryId(String repositoryUrl) {
        URI uri = URI.create(repositoryUrl);
        String path = uri.getPath();
        if (uri.getHost() == null || path == null || path.isBlank()) {
            throw new IllegalArgumentException("repository URL cannot be mapped to a registered repository");
        }
        String name = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
        if (!name.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("repository URL cannot be mapped to a registered repository");
        }
        return name;
    }

    private static String digest(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("required delivery evidence is missing: " + file.getFileName());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("cannot digest delivery evidence", exception);
        }
    }
}
