package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-factory.observability.task-queues")
public record TaskQueueObservabilityProperties(int workerCapacity) {
    public TaskQueueObservabilityProperties {
        if (workerCapacity < 1 || workerCapacity > 1_000) {
            throw new IllegalArgumentException("Task queue worker capacity must be between 1 and 1000");
        }
    }
}
