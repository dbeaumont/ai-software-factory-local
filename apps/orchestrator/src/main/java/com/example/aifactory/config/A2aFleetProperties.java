package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Fail-closed activation and bounded timeout for global A2A fleet readiness. */
@ConfigurationProperties("ai-factory.a2a.fleet")
public record A2aFleetProperties(boolean enabled, Duration readinessTimeout) {
    public A2aFleetProperties {
        readinessTimeout = readinessTimeout == null ? Duration.ofSeconds(3) : readinessTimeout;
        if (readinessTimeout.isZero() || readinessTimeout.isNegative()
                || readinessTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("A2A fleet readiness timeout must be between 1 ms and 10 seconds");
        }
    }
}
