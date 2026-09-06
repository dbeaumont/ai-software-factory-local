package com.example.aifactory.a2a;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed value carried under the project-owned A2A execution-context extension. */
public record A2aExecutionContext(String schemaVersion, String taskId, String attemptId, String workflowId,
                                  String workflowRunId, String repositoryId, String sourceCommit,
                                  String delegationId, String parentDelegationId, String agentRole,
                                  List<String> inputDigests) {
    private static final Set<String> ROLES = Set.of(
            "supervisor", "architecture-agent", "impact-analysis", "dependencies-contracts", "code-agent",
            "developer", "patch-repair", "test-agent", "test-design", "test-evidence", "security-agent",
            "threat-model", "security-findings", "independent-reviewer");

    public A2aExecutionContext {
        if (!"1".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported execution context version");
        for (Map.Entry<String, String> identity : Map.of(
                "taskId", taskId, "attemptId", attemptId, "workflowId", workflowId,
                "workflowRunId", workflowRunId, "repositoryId", repositoryId,
                "delegationId", delegationId).entrySet()) requireId(identity.getKey(), identity.getValue());
        if (parentDelegationId != null) requireId("parentDelegationId", parentDelegationId);
        if (sourceCommit == null || !sourceCommit.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("sourceCommit must be a full lowercase commit SHA");
        }
        if (!ROLES.contains(agentRole)) throw new IllegalArgumentException("Unknown A2A agent role");
        inputDigests = inputDigests == null ? List.of() : List.copyOf(inputDigests);
        if (inputDigests.isEmpty() || inputDigests.size() > 32 || Set.copyOf(inputDigests).size() != inputDigests.size()
                || inputDigests.stream().anyMatch(value -> value == null || !value.matches("[a-f0-9]{64}"))) {
            throw new IllegalArgumentException("inputDigests must contain 1 to 32 unique SHA-256 values");
        }
    }

    public Map<String, Object> asMetadata(ObjectMapper mapper) {
        @SuppressWarnings("unchecked") Map<String, Object> value = mapper.convertValue(this, Map.class);
        return Map.of(A2aExtensions.EXECUTION_CONTEXT_V1, Map.copyOf(value));
    }

    public static A2aExecutionContext fromMetadata(Map<String, Object> metadata, ObjectMapper mapper) {
        if (metadata == null || !(metadata.get(A2aExtensions.EXECUTION_CONTEXT_V1) instanceof Map<?, ?> value)) {
            throw new IllegalArgumentException("Missing A2A execution context extension");
        }
        return mapper.convertValue(value, A2aExecutionContext.class);
    }

    private static void requireId(String field, String value) {
        if (value == null || value.length() > 200 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
