package com.example.aifactory.a2a;

import java.time.Instant;

/** Non-secret, operation-bound handle that may safely be recorded in Temporal history. */
public record A2aAuthGrant(String grantId, String agentRole, String taskId, String contextId,
                           String operation, Instant expiresAt, String bindingDigest) {
    public A2aAuthGrant {
        if (grantId == null || !grantId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("A2A auth grant ID is invalid");
        }
        if (agentRole == null || agentRole.isBlank() || taskId == null || taskId.isBlank()
                || contextId == null || contextId.isBlank() || operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("A2A auth grant binding is incomplete");
        }
        if (expiresAt == null || bindingDigest == null || !bindingDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A2A auth grant expiry or digest is invalid");
        }
    }
}
