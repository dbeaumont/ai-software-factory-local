package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureAgentsTest {
    @Test
    void hostInjectsOnlyTheCataloguedContextReadsForEachArchitectureRole() {
        RecordingExecutor runtime = new RecordingExecutor();
        AgentCatalog catalog = new AgentCatalog();
        ArchitectureAgents agents = new ArchitectureAgents(runtime, catalog);

        for (String role : List.of("architecture-agent", "impact-analysis", "dependencies-contracts")) {
            agents.execute(request(role));
        }

        assertThat(runtime.invocations).hasSize(3);
        runtime.invocations.forEach(invocation -> {
            assertThat(invocation.allowedTools()).containsExactlyInAnyOrderElementsOf(
                    catalog.require(invocation.role()).tools());
            assertThat(invocation.allowedTools()).allMatch(tool -> tool.startsWith("context."));
            assertThat(invocation.promptName()).isEqualTo(invocation.role());
        });
        assertThat(runtime.invocations).extracting(AgentRuntime.Invocation::outputContract)
                .containsExactly("architecture-assessment-v1", "specialist-result-v1", "specialist-result-v1");
        assertThat(new AgentContextToolHost(null, null, null).definitions())
                .extracting(LlmGatewayClient.ToolDefinition::name).contains("context.get_symbols");
    }

    @Test
    void rejectsARoleOutsideTheArchitecturePerimeterBeforeRuntime() {
        RecordingExecutor runtime = new RecordingExecutor();
        ArchitectureAgents agents = new ArchitectureAgents(runtime, new AgentCatalog());

        assertThatThrownBy(() -> agents.execute(request("developer")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outside");
        assertThat(runtime.invocations).isEmpty();
    }

    private static ArchitectureAgents.Request request(String role) {
        return new ArchitectureAgents.Request("task-1", "attempt-1", "a".repeat(40), role,
                Set.of("specialist-1"), "untrusted input",
                new AgentToolLoop.Budget(4, Duration.ofMinutes(2), 4_000, 1_000_000));
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            return new AgentRuntime.Result(null, "f".repeat(64), 1, 10, 1);
        }
    }
}
