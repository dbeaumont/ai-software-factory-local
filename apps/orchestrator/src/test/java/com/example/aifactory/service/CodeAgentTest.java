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

class CodeAgentTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources").normalize();

    @Test
    @SuppressWarnings("unchecked")
    void coordinatesDeveloperTasksThroughAnExplicitNonEffectfulProposal() throws Exception {
        AgentCatalog catalog = new AgentCatalog();
        RecordingExecutor runtime = new RecordingExecutor();
        new CodeAgent(runtime, catalog).coordinate(new CodeAgent.Request(
                "task-1", "attempt-1", "a".repeat(40), Set.of("plan-1", "node-1", "assessment-1"),
                "untrusted architecture assessment",
                new AgentToolLoop.Budget(4, Duration.ofMinutes(2), 4_000, 1_000_000)));

        AgentRuntime.Invocation invocation = runtime.invocations.getFirst();
        assertThat(invocation.role()).isEqualTo("code-agent");
        assertThat(invocation.outputContract()).isEqualTo("integration-proposal-v1");
        assertThat(invocation.allowedTools()).containsExactlyInAnyOrderElementsOf(
                catalog.require("code-agent").tools());
        assertThat(invocation.allowedTools()).allMatch(tool -> tool.startsWith("context."));

        Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(
                Files.readString(RESOURCES.resolve("agents/code-agent.yaml")));
        assertThat(manifest).containsEntry("role", "code-agent").containsEntry("effectful", false);
        assertThat((List<String>) manifest.get("may_delegate_to"))
                .containsExactly("developer", "patch-repair");
        assertThat((List<String>) manifest.get("output_contracts"))
                .containsExactly("integration-proposal-v1");
        assertThat(Files.readString(RESOURCES.resolve(manifest.get("prompt").toString())))
                .contains("# Code Agent v1", "coordinateur logique", "integration-proposal-v1");
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            return new AgentRuntime.Result(null, "f".repeat(64), 1, 10, 1);
        }
    }
}
