package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Signed identity claims carried by every public Agent Card. */
@ConfigurationProperties("ai-factory.agent-runtime.card-identity")
public record A2aCardIdentityProperties(
        String issuer,
        String providerName,
        URI providerUrl,
        Duration validity) {
}
