package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentManifest;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes only the configured role, never credentials or mutable runtime state. */
@Component("agentRuntimeIdentity")
final class RuntimeIdentityHealthIndicator implements HealthIndicator {
    private final AgentManifest manifest;

    RuntimeIdentityHealthIndicator(AgentManifest manifest) {
        this.manifest = manifest;
    }

    @Override
    public Health health() {
        return Health.up().withDetail("role", manifest.role())
                .withDetail("manifest", manifest.manifestId()).build();
    }
}
