package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** Immutable process identity and externally advertised A2A endpoint. */
@ConfigurationProperties("ai-factory.agent-runtime")
public record AgentRuntimeProperties(String role, URI endpoint) {}
