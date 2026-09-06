package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Fixed orchestrator callback and bounded delivery policy. */
@ConfigurationProperties("ai-factory.agent-runtime.push-notification")
public record A2aPushNotificationProperties(
        boolean enabled,
        URI callback,
        String hmacSecretFile,
        int maxAttempts,
        Duration initialBackoff) {
}
