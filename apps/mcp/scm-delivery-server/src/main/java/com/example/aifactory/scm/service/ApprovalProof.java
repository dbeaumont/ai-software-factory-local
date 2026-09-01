package com.example.aifactory.scm.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public record ApprovalProof(
        @JsonProperty("schema_version") String schemaVersion,
        @JsonProperty("task_id") String taskId,
        @JsonProperty("attempt_id") String attemptId,
        @JsonProperty("repository_id") String repositoryId,
        @JsonProperty("source_sha") String sourceSha,
        @JsonProperty("patch_digest") String patchDigest,
        String decision,
        String approver,
        @JsonProperty("approved_at") Instant approvedAt,
        @JsonProperty("expires_at") Instant expiresAt,
        String signature) {

    public static ApprovalProof sign(String taskId, String attemptId, String repositoryId, String sourceSha,
                                     String patchDigest, String approver, Instant approvedAt, Instant expiresAt,
                                     byte[] key) {
        ApprovalProof unsigned = new ApprovalProof("1", taskId, attemptId, repositoryId, sourceSha, patchDigest,
                "APPROVED", approver, approvedAt, expiresAt, "");
        return new ApprovalProof("1", taskId, attemptId, repositoryId, sourceSha, patchDigest, "APPROVED", approver,
                approvedAt, expiresAt, unsigned.expectedSignature(key));
    }

    public void verify(byte[] key, Instant now) {
        if (!"1".equals(schemaVersion) || !"APPROVED".equals(decision)
                || taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,64}")
                || attemptId == null || !attemptId.matches("[A-Za-z0-9_-]{1,128}")
                || repositoryId == null || !repositoryId.matches("[a-z0-9][a-z0-9-]{1,62}")
                || sourceSha == null || !sourceSha.matches("[0-9a-f]{40}")
                || patchDigest == null || !patchDigest.matches("[0-9a-f]{64}")
                || approver == null || approver.isBlank() || approver.length() > 128
                || approvedAt == null || expiresAt == null || !expiresAt.isAfter(now)
                || expiresAt.isAfter(approvedAt.plus(Duration.ofHours(24)))) {
            throw new SecurityException("approval proof is invalid or expired");
        }
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(signature);
        } catch (Exception exception) {
            throw new SecurityException("approval proof signature is invalid");
        }
        byte[] expected = HexFormat.of().parseHex(expectedSignature(key));
        if (!java.security.MessageDigest.isEqual(expected, supplied)) {
            throw new SecurityException("approval proof signature is invalid");
        }
    }

    private String expectedSignature(byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("approval proof signature cannot be computed", exception);
        }
    }

    private String canonical() {
        return String.join("\n", schemaVersion, taskId, attemptId, repositoryId, sourceSha, patchDigest, decision,
                approver, approvedAt.toString(), expiresAt.toString());
    }
}
