package com.example.aifactory.a2a;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class A2aFleetReadinessHealthIndicatorTest {
    @Test
    void explainsEveryRoleAndDependencyBlockingAdmissions() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        A2aFleetReadinessHealthIndicator.Probe probe = new A2aFleetReadinessHealthIndicator.Probe() {
            @Override public Map<String, String> dependencies() {
                return Map.of("temporalNamespace", "READY", "oauth2", "INVALID");
            }

            @Override public A2aFleetReadinessHealthIndicator.RoleStatus role(String role) {
                return "reviewer".equals(role)
                        ? new A2aFleetReadinessHealthIndicator.RoleStatus("INVALID", "NO_POLLER", 0, 0)
                        : new A2aFleetReadinessHealthIndicator.RoleStatus("READY", "READY", 2, 2);
            }
        };
        var indicator = new A2aFleetReadinessHealthIndicator(Set.of("developer", "reviewer"), probe, meters);

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("admissions", "SUSPENDED");
        assertThat(health.getDetails().get("blockers").toString())
                .contains("oauth2", "reviewer", "agent-card", "a2a-agent-reviewer-v1");
        assertThat(meters.get("ai.factory.a2a.fleet.ready").gauge().value()).isZero();
        assertThat(meters.get("ai.factory.a2a.fleet.blocked.roles").gauge().value()).isEqualTo(1);
        assertThat(meters.get("ai.factory.a2a.fleet.blocked.dependencies").gauge().value()).isEqualTo(1);
    }

    @Test
    void opensOnlyWhenAllMandatoryChecksAreReady() {
        A2aFleetReadinessHealthIndicator.Probe probe = new A2aFleetReadinessHealthIndicator.Probe() {
            @Override public Map<String, String> dependencies() { return Map.of("temporalNamespace", "READY"); }
            @Override public A2aFleetReadinessHealthIndicator.RoleStatus role(String role) {
                return new A2aFleetReadinessHealthIndicator.RoleStatus("READY", "READY", 1, 1);
            }
        };
        var health = new A2aFleetReadinessHealthIndicator(
                Set.of("developer"), probe, new SimpleMeterRegistry()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("admissions", "OPEN");
    }
}
