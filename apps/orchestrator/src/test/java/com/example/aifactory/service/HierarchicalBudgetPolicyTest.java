package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HierarchicalBudgetPolicyTest {
    private final HierarchicalBudgetPolicy policy = new HierarchicalBudgetPolicy();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void budgetsEveryModelBackedCatalogRole() {
        Set<String> expected = new HashSet<>(new AgentCatalog().roles().keySet());
        expected.remove("workflow");

        assertThat(policy.policyId()).isEqualTo("hierarchical-budget-policy-v1");
        assertThat(policy.agents().keySet()).isEqualTo(expected);
        assertThat(policy.perimeters().keySet()).containsExactlyInAnyOrder(
                "architecture-agent", "code-agent", "test-agent", "security-agent");
        assertThat(policy.actualUsage()).isEqualTo(
                new HierarchicalBudgetPolicy.UsageQuota(120_000, 40_000, 80_000_000, 60, 208));
    }

    @Test
    void rejectsAnInvocationAboveItsAgentCeiling() {
        assertThatThrownBy(() -> policy.validateInvocation("test-evidence",
                new AgentToolLoop.Budget(4, Duration.ofMinutes(5), 4_000, 4_000_000)))
                .hasMessageContaining("agent budget exceeded").hasMessageContaining("test-evidence");
    }

    @Test
    void rejectsDelegationPerimeterAndWholeTaskOverruns() throws Exception {
        assertThatThrownBy(() -> policy.validateDelegation("developer", mapper.readTree("""
                {"max_turns":6,"max_tokens":12001,"max_cost_micros":12000000,
                 "timeout_seconds":900,"max_tool_calls":24}
                """))).hasMessageContaining("delegation budget exceeded");

        assertThatThrownBy(() -> policy.validatePerimeter("architecture-agent",
                new HierarchicalBudgetPolicy.Usage(14, 22_001, 22_000_000, 56)))
                .hasMessageContaining("perimeter architecture-agent budget exceeded");

        assertThatThrownBy(() -> policy.validateTask(
                new HierarchicalBudgetPolicy.Usage(60, 80_001, 80_000_000, 208)))
                .hasMessageContaining("task budget exceeded");
    }
}
