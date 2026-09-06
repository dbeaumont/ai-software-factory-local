package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("ai-factory.agent-runtime.rate-limit")
public record A2aRateLimitProperties(
        Duration window,
        int requestsPerIdentity,
        int pollsPerIdentity,
        int cancellationsPerIdentity,
        int notificationsPerRole,
        int maxTrackedIdentities) {
    public A2aRateLimitProperties {
        window = window == null ? Duration.ofMinutes(1) : window;
        if (window.isZero() || window.isNegative() || window.compareTo(Duration.ofMinutes(10)) > 0
                || requestsPerIdentity < 1 || pollsPerIdentity < 1 || cancellationsPerIdentity < 1
                || notificationsPerRole < 1 || maxTrackedIdentities < 1 || maxTrackedIdentities > 100_000) {
            throw new IllegalArgumentException("A2A rate-limit configuration is invalid");
        }
    }

    static A2aRateLimitProperties defaults() {
        return new A2aRateLimitProperties(Duration.ofMinutes(1), 120, 60, 10, 120, 10_000);
    }
}
