package com.example.aifactory.agentruntime;

import io.micrometer.core.instrument.Tags;

import java.util.Set;

/** Closed metric dimensions. Execution identifiers are intentionally absent from this type. */
record A2aMetricDimensions(String role, String skill, String operation, String version, String state) {
    private static final Set<String> ROLES = Set.of(
            "supervisor", "architecture-agent", "impact-analysis", "dependencies-contracts", "code-agent",
            "developer", "patch-repair", "test-agent", "test-design", "test-evidence", "security-agent",
            "threat-model", "security-findings", "independent-reviewer");
    private static final Set<String> OPERATIONS = Set.of(
            "send", "continue", "get", "list", "cancel", "execute", "notify", "reconcile", "card");
    private static final Set<String> STATES = Set.of(
            "none", "submitted", "working", "input_required", "auth_required", "completed", "rejected",
            "failed", "canceled");

    A2aMetricDimensions {
        if (!ROLES.contains(role)) throw new IllegalArgumentException("Unbounded A2A metric role");
        if (skill == null || skill.length() > 128 || !skill.matches("[a-z0-9.-]+")) {
            throw new IllegalArgumentException("Unbounded A2A metric skill");
        }
        if (!OPERATIONS.contains(operation)) throw new IllegalArgumentException("Unbounded A2A metric operation");
        if (!"1.0".equals(version)) throw new IllegalArgumentException("Unbounded A2A metric version");
        if (!STATES.contains(state)) throw new IllegalArgumentException("Unbounded A2A metric state");
    }

    Tags tags() {
        return Tags.of("agent.role", role, "agent.skill", skill, "rpc.operation", operation,
                "a2a.version", version, "task.state", state);
    }
}
