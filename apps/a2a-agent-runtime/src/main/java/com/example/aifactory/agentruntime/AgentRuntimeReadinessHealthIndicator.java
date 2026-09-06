package com.example.aifactory.agentruntime;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict readiness aggregate. Failure detail exposes component names only. */
final class AgentRuntimeReadinessHealthIndicator implements HealthIndicator {
    private final List<ReadinessCheck> checks;
    private final A2aServerMetrics metrics;

    AgentRuntimeReadinessHealthIndicator(List<ReadinessCheck> checks) {
        this(checks, A2aServerMetrics.disabled());
    }

    AgentRuntimeReadinessHealthIndicator(List<ReadinessCheck> checks, A2aServerMetrics metrics) {
        this.checks = List.copyOf(checks);
        this.metrics = metrics;
    }

    @Override
    public Health health() {
        Map<String, String> status = new LinkedHashMap<>();
        boolean ready = true;
        for (ReadinessCheck check : checks) {
            try {
                check.probe().run();
                status.put(check.name(), "UP");
            } catch (RuntimeException failure) {
                ready = false;
                status.put(check.name(), "DOWN");
            }
        }
        metrics.readiness(ready);
        Health.Builder result = ready ? Health.up() : Health.down();
        return result.withDetail("components", Map.copyOf(status)).build();
    }

    record ReadinessCheck(String name, Runnable probe) {
        ReadinessCheck {
            if (name == null || name.isBlank() || probe == null) {
                throw new IllegalArgumentException("Readiness check is incomplete");
            }
        }
    }
}
