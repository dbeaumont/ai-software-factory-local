package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeReadinessHealthIndicatorTest {

    @Test
    void readinessFailsClosedAndNamesOnlyTheUnavailableDependency() {
        AgentRuntimeReadinessHealthIndicator indicator = new AgentRuntimeReadinessHealthIndicator(List.of(
                check("agentCard", () -> { }),
                check("taskStore", () -> { throw new IllegalStateException("jdbc:secret"); }),
                check("temporal", () -> { }), check("taskQueue", () -> { }),
                check("llm", () -> { }), check("mcp", () -> { })));

        var health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().toString()).contains("taskStore=DOWN", "agentCard=UP")
                .doesNotContain("jdbc:secret");
    }

    @Test
    void readinessIsUpOnlyWhenEveryMandatoryProbePasses() {
        AgentRuntimeReadinessHealthIndicator indicator = new AgentRuntimeReadinessHealthIndicator(List.of(
                check("agentCard", () -> { }), check("taskStore", () -> { }), check("temporal", () -> { }),
                check("taskQueue", () -> { }), check("llm", () -> { }), check("mcp", () -> { })));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    private static AgentRuntimeReadinessHealthIndicator.ReadinessCheck check(String name, Runnable probe) {
        return new AgentRuntimeReadinessHealthIndicator.ReadinessCheck(name, probe);
    }
}
