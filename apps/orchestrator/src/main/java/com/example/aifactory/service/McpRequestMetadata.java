package com.example.aifactory.service;

import java.time.Duration;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

final class McpRequestMetadata {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String taskId;
    private final String sourceCommit;
    private final String actor;
    private final String attemptId;
    private final String traceId;
    private final String traceparent;
    private final String deadline;
    private final ExecutionIdentity identity;

    private McpRequestMetadata(String taskId, String sourceCommit, String actor, Duration lifetime) {
        this(taskId, sourceCommit, actor, lifetime, null);
    }

    private McpRequestMetadata(String taskId, String sourceCommit, String actor, Duration lifetime,
                               ExecutionIdentity identity) {
        this.taskId = taskId;
        this.sourceCommit = sourceCommit;
        this.actor = actor;
        this.attemptId = randomHex(16);
        this.identity = identity == null
                ? ExecutionIdentity.deterministic(taskId, attemptId, actor, actor)
                : identity;
        this.traceId = this.identity.traceId();
        String spanId = randomHex(8);
        this.traceparent = "00-" + traceId + '-' + spanId + "-01";
        this.deadline = Instant.now().plus(lifetime).toString();
    }

    static McpRequestMetadata create(String taskId, String sourceCommit, String actor, Duration lifetime) {
        return new McpRequestMetadata(taskId, sourceCommit, actor, lifetime);
    }

    static McpRequestMetadata create(String taskId, String sourceCommit, String actor, Duration lifetime,
                                     ExecutionIdentity identity) {
        return new McpRequestMetadata(taskId, sourceCommit, actor, lifetime, identity);
    }

    Map<String, Object> arguments() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schema_version", "1");
        arguments.put("task_id", taskId);
        arguments.put("attempt_id", attemptId);
        arguments.put("source_commit", sourceCommit);
        arguments.put("actor", actor);
        arguments.put("trace_id", traceId);
        arguments.put("traceparent", traceparent);
        arguments.put("run_id", identity.runId());
        arguments.put("delegation_id", identity.delegationId());
        arguments.put("agent_run_id", identity.agentRunId());
        arguments.put("deadline", deadline);
        return arguments;
    }

    private static String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
