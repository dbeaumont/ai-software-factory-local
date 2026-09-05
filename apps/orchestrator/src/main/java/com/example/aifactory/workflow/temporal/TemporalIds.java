package com.example.aifactory.workflow.temporal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Canonical identifiers shared by clients, workflows, child workflows and effectful activities. */
public final class TemporalIds {
    private static final int MAX_ID_LENGTH = 200;

    private TemporalIds() {}

    public static String workflow(String taskId, String attemptId) {
        String value = "ai-factory/" + require(taskId) + '/' + require(attemptId);
        return value.length() <= MAX_ID_LENGTH ? value : "ai-factory/" + digest(value);
    }

    public static String delegation(String taskId, String attemptId, String nodeId) {
        return bounded("delegation", require(taskId), require(attemptId), require(nodeId));
    }

    public static String activity(String taskId, String attemptId, String nodeId, String operation, int sequence) {
        if (sequence < 0) throw new IllegalArgumentException("Activity sequence cannot be negative");
        return bounded("activity", require(taskId), require(attemptId), require(nodeId), require(operation),
                Integer.toString(sequence));
    }

    public static String effectKey(String taskId, String attemptId, String nodeId, String operation, int sequence) {
        return bounded("effect", activity(taskId, attemptId, nodeId, operation, sequence));
    }

    private static String bounded(String prefix, String... parts) {
        String value = prefix + '-' + String.join("-", parts);
        if (value.length() <= MAX_ID_LENGTH) return value;
        return prefix + '-' + digest(value);
    }

    private static String require(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Temporal identifier component is invalid");
        }
        return value;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot build deterministic Temporal identifier", exception);
        }
    }
}
