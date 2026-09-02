package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TestAgentsTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources").normalize();

    @Test
    @SuppressWarnings("unchecked")
    void separatesCoordinatorDesignAndEvidenceRolesWithDistinctContracts() throws Exception {
        tools.jackson.databind.JsonNode strategy = new tools.jackson.databind.ObjectMapper().readTree(
                Files.readString(RESOURCES.resolve("multiagents/fixtures/golden-contracts-v1.json")))
                .path("documents").path("test-strategy-v1");
        RecordingExecutor runtime = new RecordingExecutor(strategy);
        AgentCatalog catalog = new AgentCatalog();
        TestAgents agents = new TestAgents(runtime, catalog, new TestStrategyValidator());
        Map<String, String> contracts = Map.of(
                "test-agent", "test-assessment-v1",
                "test-design", "test-strategy-v1",
                "test-evidence", "test-assessment-v1");

        for (String role : contracts.keySet()) {
            agents.execute(new TestAgents.Request("task-1", "attempt-1", "a".repeat(40), role,
                    Set.of("strategy-1"), "untrusted input",
                    new AgentToolLoop.Budget(3, Duration.ofMinutes(2), 3000, 1000000)));
            Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(
                    Files.readString(RESOURCES.resolve("agents/" + role + ".yaml")));
            assertThat((List<String>) manifest.get("output_contracts")).containsExactly(contracts.get(role));
            assertThat(Files.readString(RESOURCES.resolve(manifest.get("prompt").toString())))
                    .contains("# Test", contracts.get(role));
        }
        assertThat(runtime.invocations).extracting(AgentRuntime.Invocation::role)
                .containsExactlyInAnyOrderElementsOf(contracts.keySet());
        AgentRuntime.Invocation design = runtime.invocations.stream()
                .filter(value -> value.role().equals("test-design")).findFirst().orElseThrow();
        AgentRuntime.Invocation evidence = runtime.invocations.stream()
                .filter(value -> value.role().equals("test-evidence")).findFirst().orElseThrow();
        assertThat(design.outputContract()).isEqualTo("test-strategy-v1");
        assertThat(design.allowedTools()).allMatch(tool -> tool.startsWith("context."));
        assertThat(evidence.outputContract()).isEqualTo("test-assessment-v1");
        assertThat(evidence.allowedTools()).containsExactly("evidence.get_summary");
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();
        private final tools.jackson.databind.JsonNode result;

        private RecordingExecutor(tools.jackson.databind.JsonNode result) { this.result = result; }

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            return new AgentRuntime.Result(result, "f".repeat(64), 1, 10, 1);
        }
    }
}
