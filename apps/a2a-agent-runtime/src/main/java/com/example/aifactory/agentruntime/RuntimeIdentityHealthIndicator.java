package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes only the configured role, never credentials or mutable runtime state. */
@Component("agentRuntimeIdentity")
final class RuntimeIdentityHealthIndicator implements HealthIndicator {
    private final AgentRuntimeProperties properties;
    private final AgentCatalog catalog;

    RuntimeIdentityHealthIndicator(AgentRuntimeProperties properties, AgentCatalog catalog) {
        this.properties = properties;
        this.catalog = catalog;
    }

    @Override
    public Health health() {
        String role = properties.role();
        if (role == null || role.isBlank()) return Health.unknown().withDetail("role", "unconfigured").build();
        try {
            AgentCatalog.Role configured = catalog.require(role);
            return configured.isAgent() ? Health.up().withDetail("role", role).build()
                    : Health.down().withDetail("role", "control-plane-forbidden").build();
        } catch (IllegalArgumentException invalid) {
            return Health.down().withDetail("role", "unknown").build();
        }
    }
}
