package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
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

class IndependentReviewerAgentTest {
    private static final Path RESOURCES = Path.of(System.getProperty("user.dir"))
            .resolve("../../resources").normalize();

    @Test
    @SuppressWarnings("unchecked")
    void formalizesTheRenamedReviewerAsAnIndependentWorkflowChild() throws Exception {
        AgentCatalog catalog = new AgentCatalog();
        AgentCatalog.Role role = catalog.require("independent-reviewer");
        Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(
                Files.readString(RESOURCES.resolve("agents/independent-reviewer.yaml")));

        assertThat(manifest).containsEntry("role", role.name())
                .containsEntry("parent", "workflow")
                .containsEntry("owner", role.owner())
                .containsEntry("autonomy", role.autonomy())
                .containsEntry("effectful", false);
        assertThat((List<String>) manifest.get("may_delegate_to")).isEmpty();
        assertThat((List<String>) manifest.get("allowed_tools"))
                .containsExactlyElementsOf(role.tools());
        assertThat((List<String>) manifest.get("output_contracts"))
                .containsExactly("independent-review-v1");
        assertThat(Files.readString(RESOURCES.resolve(manifest.get("prompt").toString())))
                .startsWith("# Independent Reviewer v1")
                .contains("Workflow Coordinator racine", "independent-review-v1");
    }

    @Test
    void invokesOnlyTheIndependentReviewerIdentityAndContract() {
        RecordingExecutor runtime = new RecordingExecutor();
        IndependentReviewerAgent reviewer = new IndependentReviewerAgent(runtime, new AgentCatalog(),
                new IndependentReviewInputBuilder(new ObjectMapper()));

        reviewer.execute(new IndependentReviewerAgent.Request(
                "task-1", "attempt-1", "a".repeat(40), bundle(),
                new AgentToolLoop.Budget(4, Duration.ofMinutes(2), 4000, 2_000_000)));

        AgentRuntime.Invocation invocation = runtime.invocations.getFirst();
        assertThat(invocation.role()).isEqualTo("independent-reviewer");
        assertThat(invocation.promptName()).isEqualTo("independent-reviewer");
        assertThat(invocation.outputContract()).isEqualTo("independent-review-v1");
        assertThat(invocation.allowedReferenceIds()).containsExactlyInAnyOrder(
                "b".repeat(64), "result-1", "contradiction-1");
        assertThat(invocation.untrustedInput()).contains(
                "\"consolidated_patch\"", "\"final_manifest\"", "\"RESOLVED\"");
        assertThat(invocation.allowedTools()).containsExactlyInAnyOrderElementsOf(
                new AgentCatalog().require("independent-reviewer").tools());
    }

    @Test
    void rejectsABundleFromAnotherWorkflowLineage() {
        IndependentReviewerAgent reviewer = new IndependentReviewerAgent(new RecordingExecutor(),
                new AgentCatalog(), new IndependentReviewInputBuilder(new ObjectMapper()));

        assertThatThrownBy(() -> reviewer.execute(new IndependentReviewerAgent.Request(
                "task-foreign", "attempt-1", "a".repeat(40), bundle(),
                new AgentToolLoop.Budget(4, Duration.ofMinutes(2), 4000, 2_000_000))))
                .isInstanceOf(SecurityException.class).hasMessageContaining("outside workflow lineage");
    }

    static IndependentReviewBundle bundle() {
        return new IndependentReviewBundle("task-1", "attempt-1", "a".repeat(40),
                new IndependentReviewBundle.ConsolidatedPatch("patch-1", "evidence://task-1/patch",
                        "c".repeat(64), List.of("src/App.java")),
                new IndependentReviewBundle.FinalManifest("b".repeat(64),
                        "evidence://task-1/manifest", "d".repeat(64)),
                List.of(new IndependentReviewBundle.ResultReference("result-1", "code-agent",
                        "evidence://task-1/result", "e".repeat(64))),
                List.of(new IndependentReviewBundle.ContradictionReference("contradiction-1", "RESOLVED",
                        "evidence://task-1/contradiction", "f".repeat(64))));
    }

    private static final class RecordingExecutor implements AgentExecutor {
        private final List<AgentRuntime.Invocation> invocations = new ArrayList<>();

        @Override public AgentRuntime.Result execute(AgentRuntime.Invocation invocation) {
            invocations.add(invocation);
            return new AgentRuntime.Result(null, "f".repeat(64), 1, 100, 10);
        }
    }
}
