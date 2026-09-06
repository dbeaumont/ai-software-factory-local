package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Process identity; stricter admission validation is added by A2A-032. */
@ConfigurationProperties("ai-factory.agent-runtime")
public record AgentRuntimeProperties(String role) {}
