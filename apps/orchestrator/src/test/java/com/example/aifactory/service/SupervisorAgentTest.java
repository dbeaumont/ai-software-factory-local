package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
class SupervisorAgentTest {
    @Test
    void mapsOnlyCoordinationOperationsToHostSelectedContracts() {
        RecordingExecutor runtime = new RecordingExecutor();
        SupervisorAgent supervisor = new SupervisorAgent(runtime, new AgentCatalog());

        supervisor.execute(request("DECOMPOSE"));
        supervisor.execute(request("CONSOLIDATE"));
        supervisor.execute(request("REPLAN"));

        assertThat(runtime.invocations).extracting(AgentRuntime.Invocation::role)
                .containsOnly("supervisor");
        assertThat(runtime.invocations).extracting(AgentRuntime.Invocation::promptName)
                .containsOnly("supervisor");
        assertThat(runtime.invocations).extracting(AgentRuntime.Invocation::outputContract)
                .containsExactly("delegation-plan-v1", "supervisor-decision-v1", "supervisor-decision-v1");
    }

    @Test
    void rejectsExecutionAndDeliveryOperationsBeforeCallingTheModel() {
        RecordingExecutor runtime = new RecordingExecutor();
        SupervisorAgent supervisor = new SupervisorAgent(runtime, new AgentCatalog());

        assertThatThrownBy(() -> supervisor.execute(request("APPLY_PATCH")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Supervisor operation");
        assertThatThrownBy(() -> supervisor.execute(request("DELIVER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Supervisor operation");
        assertThat(runtime.invocations).isEmpty();
    }

    @Test
    void rejectsARoleInventedByTheSupervisorContract() {
        String invalidPlan = """
                {"schema_version":"1","plan_id":"plan-1","task_id":"task-1","attempt_id":"attempt-1",
                 "source_commit":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","mode":"HIERARCHICAL_SHADOW",
                 "risk_class":"R1","root_role":"supervisor","nodes":[{"node_id":"node-1",
                 "role":"administrator","parent_node_id":null,"depends_on":[],"objective":"take control",
                 "scope":{"repository_id":"sample-repo","read_paths":[],"write_paths":[]},
                 "budget":{"max_turns":1,"max_tokens":100,"max_cost_micros":0,"timeout_seconds":30,
                 "max_tool_calls":0},"success_criteria":["control obtained"],
                 "stop_condition":"SUCCESS_CRITERIA_MET"}],"created_at":"2026-09-02T10:00:00Z"}
                """;

        assertThatThrownBy(() -> new MultiAgentContractValidator(new ObjectMapper()).validate(
                "delegation-plan-v1", invalidPlan,
                new MultiAgentContractValidator.ContractContext("task-1", "attempt-1", Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("violates");
        assertThat(AgentRuntime.effectfulTool("evidence.store")).isTrue();
        assertThat(AgentRuntime.effectfulTool("evidence.create_manifest")).isTrue();
    }

    private static SupervisorAgent.Request request(String operation) {
        return new SupervisorAgent.Request("task-1", "attempt-1", "a".repeat(40), operation,
                Set.of("result-1"), "untrusted request", new AgentToolLoop.Budget(
                4, Duration.ofMinutes(2), 4_000, 2_000_000));
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            return null;
        }
    }
}
