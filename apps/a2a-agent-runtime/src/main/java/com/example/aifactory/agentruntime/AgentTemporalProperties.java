package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Dedicated Temporal connectivity and worker-version identity for one agent role. */
@ConfigurationProperties("ai-factory.agent-runtime.temporal")
public record AgentTemporalProperties(
        boolean enabled, String target, String namespace, String deploymentName, String buildId,
        Duration recoveryInterval) {

    public AgentTemporalProperties {
        recoveryInterval = recoveryInterval == null ? Duration.ofSeconds(15) : recoveryInterval;
    }

    public String taskQueue(String role) { return "a2a-agent-" + role + "-v1"; }

    public void validate(String role) {
        if (!enabled) return;
        if (target == null || target.isBlank() || namespace == null || namespace.isBlank()
                || deploymentName == null || !deploymentName.matches("[A-Za-z0-9._-]{1,128}")
                || buildId == null || !buildId.matches("[A-Za-z0-9._-]{1,128}")
                || recoveryInterval.compareTo(Duration.ofSeconds(5)) < 0
                || recoveryInterval.compareTo(Duration.ofMinutes(5)) > 0
                || role == null || role.isBlank()) {
            throw new IllegalStateException("Agent Temporal configuration is incomplete");
        }
    }
}
