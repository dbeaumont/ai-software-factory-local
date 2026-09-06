package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Effective transport and token policy advertised by the Agent Card. */
@ConfigurationProperties("ai-factory.agent-runtime.security")
public record A2aSecurityProperties(
        boolean enabled,
        boolean mtlsRequired,
        URI oauth2Issuer,
        URI oauth2TokenUrl,
        String audience,
        Duration maximumTokenLifetime) {
}
