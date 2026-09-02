package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchRepairAgentTest {
    private static final Path FIXTURES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MultiAgentContractValidator contracts = new MultiAgentContractValidator(mapper);

    @Test
    void bindsRepairToTheExactDelegationWorktreeAndAttempt() throws Exception {
        JsonNode documents = mapper.readTree(Files.readString(FIXTURES)).path("documents");
        RecordingExecutor runtime = new RecordingExecutor(documents.path("patch-repair-proposal-v1"));

        new PatchRepairAgent(runtime, new AgentCatalog(), contracts)
                .execute(request(documents.path("patch-repair-task-v1").toString()));

        AgentRuntime.Invocation invocation = runtime.invocations.getFirst();
        assertThat(invocation.role()).isEqualTo("patch-repair");
        assertThat(invocation.promptName()).isEqualTo("patch-repair-hierarchical");
        assertThat(invocation.outputContract()).isEqualTo("patch-repair-proposal-v1");
        assertThat(invocation.untrustedInput()).contains(
                "\"worktree_id\":\"worktree-code-1\"", "\"repair_attempt\":1");
    }

    @Test
    void rejectsARepairThatChangesAnyExecutionBinding() throws Exception {
        JsonNode documents = mapper.readTree(Files.readString(FIXTURES)).path("documents");
        for (String field : List.of("attempt_id", "node_id", "worktree_id", "repair_attempt")) {
            var divergent = documents.path("patch-repair-proposal-v1").deepCopy();
            if ("repair_attempt".equals(field)) {
                ((tools.jackson.databind.node.ObjectNode) divergent).put(field, 2);
            } else {
                ((tools.jackson.databind.node.ObjectNode) divergent).put(field, "other-binding");
            }
            PatchRepairAgent repair = new PatchRepairAgent(
                    new RecordingExecutor(divergent), new AgentCatalog(), contracts);
            assertThatThrownBy(() -> repair.execute(request(
                    documents.path("patch-repair-task-v1").toString())))
                    .as(field).isInstanceOfAny(SecurityException.class, IllegalArgumentException.class);
        }
    }

    private static PatchRepairAgent.Request request(String task) {
        return new PatchRepairAgent.Request("task-1", "attempt-1", "a".repeat(40), task,
                Set.of("plan-1", "repair-node-1", "code-1", "proposal-1"),
                new AgentToolLoop.Budget(2, Duration.ofMinutes(2), 2_000, 1_000_000));
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final JsonNode result;
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();

        private RecordingExecutor(JsonNode result) { this.result = result; }

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            return new AgentRuntime.Result(result, "f".repeat(64), 1, 10, 1);
        }
    }
}
