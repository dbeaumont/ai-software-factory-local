package com.example.aifactory.scm.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalProofTest {
    private static final byte[] KEY = "test-approval-key-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);

    @Test
    void verifiesAllBoundFieldsAndRejectsTamperingOrExpiration() {
        Instant approvedAt = Instant.parse("2026-09-02T00:00:00Z");
        ApprovalProof proof = ApprovalProof.sign("task-1", "attempt-1", "customer-api", "a".repeat(40),
                "b".repeat(64), "David Beaumont", approvedAt, approvedAt.plusSeconds(3600), KEY);

        assertDoesNotThrow(() -> proof.verify(KEY, approvedAt.plusSeconds(10)));
        ApprovalProof tampered = new ApprovalProof(proof.schemaVersion(), proof.taskId(), proof.attemptId(),
                proof.repositoryId(), "c".repeat(40), proof.patchDigest(), proof.decision(), proof.approver(),
                proof.approvedAt(), proof.expiresAt(), proof.signature());
        assertThrows(SecurityException.class, () -> tampered.verify(KEY, approvedAt.plusSeconds(10)));
        assertThrows(SecurityException.class, () -> proof.verify(KEY, approvedAt.plusSeconds(3601)));
    }
}
