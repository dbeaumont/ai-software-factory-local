package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
