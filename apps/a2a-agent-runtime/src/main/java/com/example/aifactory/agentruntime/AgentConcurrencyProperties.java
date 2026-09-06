package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ai-factory.agent-runtime.concurrency")
public record AgentConcurrencyProperties(
        int workflowPollers, int activityPollers, int maxWorkflowExecutions, int maxActivityExecutions,
        int maxActiveTasksPerTenant, int maxQueuedTasks, Duration shutdownGrace) {
    public AgentConcurrencyProperties {
        if (workflowPollers < 1 || workflowPollers > 32 || activityPollers < 1 || activityPollers > 32
                || maxWorkflowExecutions < 1 || maxWorkflowExecutions > 1_000
                || maxActivityExecutions < 1 || maxActivityExecutions > 1_000
                || maxActiveTasksPerTenant < 1 || maxActiveTasksPerTenant > 10_000
                || maxQueuedTasks < maxActiveTasksPerTenant || maxQueuedTasks > 100_000
                || shutdownGrace == null || shutdownGrace.isNegative() || shutdownGrace.isZero()
                || shutdownGrace.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Agent runtime concurrency limits are invalid");
        }
    }
}
