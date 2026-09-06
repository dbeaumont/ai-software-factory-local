package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dedicated task-projection database; never points at Temporal's internal schema. */
@ConfigurationProperties("ai-factory.agent-runtime.task-store")
public record A2aTaskStoreProperties(
        boolean enabled, String jdbcUrl, String username, String password, int maximumPoolSize) {}
