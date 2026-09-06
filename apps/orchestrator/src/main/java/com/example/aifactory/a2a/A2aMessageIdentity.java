package com.example.aifactory.a2a;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Stable idempotency identity for one logical A2A send. */
public final class A2aMessageIdentity {
    private A2aMessageIdentity() {}

    public static String derive(A2aExecutionContext execution, String role, String skill,
                                int sequence, String inputDigest) {
        if (execution == null || !execution.agentRole().equals(role) || skill == null || skill.isBlank()
                || sequence < 0 || inputDigest == null || !inputDigest.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Message identity inputs are invalid");
        }
        byte[] hash = sha256(String.join("\n", execution.taskId(), execution.attemptId(),
                execution.workflowId(), execution.workflowRunId(), execution.delegationId(), role, skill,
                Integer.toString(sequence), inputDigest));
        ByteBuffer bytes = ByteBuffer.wrap(hash);
        long most = bytes.getLong();
        long least = bytes.getLong();
        most = (most & 0xffffffffffff0fffL) | 0x0000000000008000L;
        least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
        return new UUID(most, least).toString();
    }

    public static String payloadDigest(byte[] canonicalPayload) {
        if (canonicalPayload == null) throw new IllegalArgumentException("Canonical payload is required");
        return HexFormat.of().formatHex(sha256(canonicalPayload));
    }

    private static byte[] sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
