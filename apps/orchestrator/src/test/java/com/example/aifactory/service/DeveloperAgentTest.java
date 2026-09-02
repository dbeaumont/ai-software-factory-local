package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeveloperAgentTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources").normalize();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MultiAgentContractValidator contracts = new MultiAgentContractValidator(mapper);

    @Test
    @SuppressWarnings("unchecked")
    void acceptsOneExplicitCodeScopeAndRequiresABoundPatchProposal() throws Exception {
        JsonNode fixtures = mapper.readTree(Files.readString(
                RESOURCES.resolve("multiagents/fixtures/golden-contracts-v1.json"))).path("documents");
        RecordingExecutor runtime = new RecordingExecutor(fixtures.path("patch-proposal-v1"));
        DeveloperAgent developer = new DeveloperAgent(runtime, new AgentCatalog(), contracts,
                new PatchScopeValidator());

        developer.execute(request(fixtures.path("code-task-v1").toString()));

        AgentRuntime.Invocation invocation = runtime.invocations.getFirst();
        assertThat(invocation.role()).isEqualTo("developer");
        assertThat(invocation.promptName()).isEqualTo("developer-hierarchical");
        assertThat(invocation.outputContract()).isEqualTo("patch-proposal-v1");
        assertThat(invocation.untrustedInput()).contains("\"scope_digest\"");

        Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(
                Files.readString(RESOURCES.resolve("agents/developer.yaml")));
        assertThat((List<String>) manifest.get("input_contracts")).containsExactly("code-task-v1");
        assertThat((List<String>) manifest.get("output_contracts")).containsExactly("patch-proposal-v1");
        assertThat(manifest).containsEntry("compatibility_prompt", "prompts/developer.md");
    }

    @Test
    void rejectsAProposalForAnotherScope() throws Exception {
        JsonNode fixtures = mapper.readTree(Files.readString(
                RESOURCES.resolve("multiagents/fixtures/golden-contracts-v1.json"))).path("documents");
        var divergent = fixtures.path("patch-proposal-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) divergent).put("scope_digest", "f".repeat(64));
        DeveloperAgent developer = new DeveloperAgent(
                new RecordingExecutor(divergent), new AgentCatalog(), contracts, new PatchScopeValidator());

        assertThatThrownBy(() -> developer.execute(request(fixtures.path("code-task-v1").toString())))
                .isInstanceOf(SecurityException.class).hasMessageContaining("not bound");
    }

    @Test
    void rejectsAProposalProducedFromAnotherDelegationsWorktree() throws Exception {
        JsonNode fixtures = mapper.readTree(Files.readString(
                RESOURCES.resolve("multiagents/fixtures/golden-contracts-v1.json"))).path("documents");
        var divergent = fixtures.path("patch-proposal-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) divergent).put(
                "worktree_id", "worktree-other-0000000000000000");
        DeveloperAgent developer = new DeveloperAgent(
                new RecordingExecutor(divergent), new AgentCatalog(), contracts, new PatchScopeValidator());

        assertThatThrownBy(() -> developer.execute(request(fixtures.path("code-task-v1").toString())))
                .isInstanceOf(SecurityException.class).hasMessageContaining("not bound");
    }

    @Test
    void rejectsEveryFileOutsideTheAssignedWriteScopeBeforeSandbox() throws Exception {
        JsonNode fixtures = mapper.readTree(Files.readString(
                RESOURCES.resolve("multiagents/fixtures/golden-contracts-v1.json"))).path("documents");
        var escaped = fixtures.path("patch-proposal-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) escaped.path("files_touched").get(0))
                .put("path", "infrastructure/deploy.yaml");
        DeveloperAgent developer = new DeveloperAgent(
                new RecordingExecutor(escaped), new AgentCatalog(), contracts, new PatchScopeValidator());

        assertThatThrownBy(() -> developer.execute(request(fixtures.path("code-task-v1").toString())))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("before sandbox")
                .hasMessageContaining("outside assigned write scope");
    }

    private static DeveloperAgent.Request request(String task) {
        return new DeveloperAgent.Request("task-1", "attempt-1", "a".repeat(40), task,
                Set.of("plan-1", "node-1", "assessment-1"),
                new AgentToolLoop.Budget(4, Duration.ofMinutes(2), 4_000, 1_000_000));
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final JsonNode result;
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();

        private RecordingExecutor(JsonNode result) {
            this.result = result;
        }

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            return new AgentRuntime.Result(result, "f".repeat(64), 1, 10, 1);
        }
    }
}
