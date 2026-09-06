package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bounded Agent Card discovery/cache policy shared by all orchestrator activities. */
@ConfigurationProperties("ai-factory.a2a.cards")
public record A2aCardCacheProperties(String registryProfile, Duration maximumTtl, Duration staleOnOutage) {
    public A2aCardCacheProperties {
        registryProfile = registryProfile == null || registryProfile.isBlank() ? "compose" : registryProfile;
        maximumTtl = maximumTtl == null ? Duration.ofMinutes(5) : maximumTtl;
        staleOnOutage = staleOnOutage == null ? Duration.ofMinutes(2) : staleOnOutage;
        if (maximumTtl.isZero() || maximumTtl.isNegative() || staleOnOutage.isNegative()
                || staleOnOutage.compareTo(maximumTtl) > 0) {
            throw new IllegalArgumentException("A2A Agent Card cache durations are invalid");
        }
    }
}
