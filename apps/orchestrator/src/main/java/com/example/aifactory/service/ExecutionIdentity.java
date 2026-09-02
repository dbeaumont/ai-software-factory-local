package com.example.aifactory.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Immutable correlation identity propagated across workflow, agent and MCP boundaries. */
public record ExecutionIdentity(String traceId, String runId, String delegationId, String agentRunId) {
    public ExecutionIdentity {
        if (traceId == null || !traceId.matches("[0-9a-f]{32}")
                || !validId(runId) || !validId(delegationId) || !validId(agentRunId)) {
            throw new IllegalArgumentException("Execution correlation identity is invalid");
        }
    }

    public static ExecutionIdentity deterministic(String taskId, String runId, String delegationId,
                                                  String agentDiscriminator) {
        if (!validId(taskId) || !validId(runId) || !validId(delegationId) || !validId(agentDiscriminator)) {
            throw new IllegalArgumentException("Execution correlation seed is invalid");
        }
        String seed = taskId + '\n' + runId + '\n' + delegationId + '\n' + agentDiscriminator;
        String digest = sha256(seed);
        return new ExecutionIdentity(digest.substring(0, 32), runId, delegationId,
                "agent-" + digest.substring(32, 56));
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
