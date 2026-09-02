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

        new PatchRepairAgent(runtime, new AgentCatalog(), contracts, new PatchScopeValidator())
                .execute(request(documents.path("patch-repair-task-v1").toString()));

        AgentRuntime.Invocation invocation = runtime.invocations.getFirst();
        assertThat(invocation.role()).isEqualTo("patch-repair");
        assertThat(invocation.promptName()).isEqualTo("patch-repair-hierarchical");
        assertThat(invocation.outputContract()).isEqualTo("patch-repair-proposal-v1");
        assertThat(invocation.untrustedInput()).contains(
                "\"worktree_id\":\"worktree-code-1\"", "\"repair_attempt\":1",
                "\"failure_kind\":\"PATCH_INVALID\"", "\"target_paths\":[\"src/App.java\"]");
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
                    new RecordingExecutor(divergent), new AgentCatalog(), contracts, new PatchScopeValidator());
            assertThatThrownBy(() -> repair.execute(request(
                    documents.path("patch-repair-task-v1").toString())))
                    .as(field).isInstanceOfAny(SecurityException.class, IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsARepairTouchingAFileOutsideItsOriginalScope() throws Exception {
        JsonNode documents = mapper.readTree(Files.readString(FIXTURES)).path("documents");
        var escaped = documents.path("patch-repair-proposal-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) escaped.path("files_touched").get(0))
                .put("path", "src/Other.java");
        PatchRepairAgent repair = new PatchRepairAgent(
                new RecordingExecutor(escaped), new AgentCatalog(), contracts, new PatchScopeValidator());

        assertThatThrownBy(() -> repair.execute(request(documents.path("patch-repair-task-v1").toString())))
                .isInstanceOf(SecurityException.class).hasMessageContaining("before sandbox");
    }

    @Test
    void rejectsAnyRepairCauseOtherThanATargetedInvalidPatchOrConflict() throws Exception {
        JsonNode documents = mapper.readTree(Files.readString(FIXTURES)).path("documents");
        var unsupported = documents.path("patch-repair-task-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) unsupported).put("failure_kind", "TEST_FAILURE");

        PatchRepairAgent repair = new PatchRepairAgent(
                new RecordingExecutor(documents.path("patch-repair-proposal-v1")),
                new AgentCatalog(), contracts, new PatchScopeValidator());

        assertThatThrownBy(() -> repair.execute(request(unsupported.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("violates");
    }

    @Test
    void acceptsOnlyAConflictBoundToBothKnownProposals() throws Exception {
        JsonNode documents = mapper.readTree(Files.readString(FIXTURES)).path("documents");
        var conflictTask = documents.path("patch-repair-task-v1").deepCopy();
        var conflictProposal = documents.path("patch-repair-proposal-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) conflictTask).put("failure_kind", "PATCH_CONFLICT");
        ((tools.jackson.databind.node.ArrayNode) conflictTask.path("conflicting_proposal_ids"))
                .add("proposal-1").add("proposal-2");
        ((tools.jackson.databind.node.ObjectNode) conflictProposal).put("failure_kind", "PATCH_CONFLICT");
        ((tools.jackson.databind.node.ArrayNode) conflictProposal.path("conflicting_proposal_ids"))
                .add("proposal-1").add("proposal-2");
        PatchRepairAgent repair = new PatchRepairAgent(
                new RecordingExecutor(conflictProposal), new AgentCatalog(), contracts, new PatchScopeValidator());

        repair.execute(request(conflictTask.toString(), Set.of(
                "plan-1", "repair-node-1", "code-1", "proposal-1", "proposal-2")));

        assertThatThrownBy(() -> repair.execute(request(conflictTask.toString())))
                .isInstanceOf(SecurityException.class).hasMessageContaining("targeted invalid patch or conflict");
    }

    private static PatchRepairAgent.Request request(String task) {
        return request(task, Set.of("plan-1", "repair-node-1", "code-1", "proposal-1"));
    }

    private static PatchRepairAgent.Request request(String task, Set<String> references) {
        return new PatchRepairAgent.Request("task-1", "attempt-1", "a".repeat(40), task, references,
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
