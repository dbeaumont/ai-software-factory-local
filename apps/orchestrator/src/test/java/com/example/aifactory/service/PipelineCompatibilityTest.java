package com.example.aifactory.service;

import com.example.aifactory.config.AgentToolingProperties;
import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PipelineCompatibilityTest {
    private static final String SOURCE_COMMIT = "a".repeat(40);
    @TempDir Path workspaces;

    @Test
    @SuppressWarnings("unchecked")
    void currentExtractedPipelineMatchesTheFrozenVersion02OutputContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(anyList(), nullable(Path.class), any(Duration.class))).thenReturn("cloned", SOURCE_COMMIT);
        RepositoryContextProvider context = (repository, taskId, sourceCommit) -> "context";
        PromptService prompts = mock(PromptService.class);
        when(prompts.load(anyString())).thenReturn("system");
        when(prompts.fingerprint(anyString())).thenReturn("b".repeat(64));
        LlmGatewayClient llm = mock(LlmGatewayClient.class);
        when(llm.modelName()).thenReturn("baseline-model");
        String plan = "{\"status\":\"IMPLEMENTABLE\"}";
        String proposal = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new";
        String tester = "{\"coverage_gaps\":[],\"test_cases\":[],\"evidence\":[],\"unverified\":[]}";
        String review = "{\"decision\":\"ACCEPT\",\"findings\":[]}";
        when(llm.chatDetailed(anyString(), anyString(), anyInt(), nullable(Map.class))).thenReturn(
                new LlmGatewayClient.LlmCallResult(plan, 10, 1, 1),
                new LlmGatewayClient.LlmCallResult(proposal, 10, 1, 1),
                new LlmGatewayClient.LlmCallResult(tester, 10, 1, 1),
                new LlmGatewayClient.LlmCallResult(review, 10, 1, 1));
        AgentResponseValidator responses = mock(AgentResponseValidator.class);
        when(responses.hasValidPlannerContract(plan)).thenReturn(true);
        when(responses.summarizeReview(review)).thenReturn(
                new AgentResponseValidator.ReviewSummary("ACCEPT", List.of()));
        FakeSandbox sandbox = new FakeSandbox();
        AssuranceGateway assurance = mock(AssuranceGateway.class);
        when(assurance.requireQualityGate(anyString(), anyString(), anyString()))
                .thenReturn(mapper.readTree("{\"verdict\":\"PASSED\"}"));
        DeterministicWorkflowCoordinator coordinator = new DeterministicWorkflowCoordinator(
                new AiFactoryProperties(null, null, "baseline-model", true, workspaces.toString(), null,
                        null, null, null, null, null, null, null),
                runner, context, prompts, llm, responses, sandbox, new PatchIntegrator(sandbox), assurance,
                mock(ScmDeliveryGateway.class), new SimpleMeterRegistry(), mapper,
                new AgentToolingProperties(Set.of(), "INCOMPLETE", 0, false, false, Set.of()),
                mock(AgentContextToolHost.class));
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "https://example.test/repo.git", "main", "change", null));

        ReflectionTestUtils.invokeMethod(coordinator, "runPipeline", state);

        Map<String, Object> expected;
        try (var input = Files.newInputStream(Path.of(
                "../../resources/multiagents/baselines/pipeline-v1-output-contract.yaml"))) {
            expected = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
        assertThat(expected.get("baselineCommit")).isEqualTo("45e72011a8cc2c81006a5ff7b8b3a3f725db5174");
        assertThat(snapshot(state)).isEqualTo(withoutMetadata(expected));
    }

    private static Map<String, Object> snapshot(TaskState state) {
        TaskView view = state.view();
        Path workspace = Path.of(state.workspace);
        List<String> artifacts;
        try (var files = Files.walk(workspace)) {
            artifacts = files.filter(Files::isRegularFile).map(workspace::relativize)
                    .map(Path::toString).sorted().toList();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        List<String> populated = new ArrayList<>();
        if (view.plan() != null) populated.add("plan");
        if (view.patch() != null) populated.add("patch");
        if (view.testSummary() != null) populated.add("testSummary");
        if (view.qualitySummary() != null) populated.add("qualitySummary");
        if (view.securitySummary() != null) populated.add("securitySummary");
        if (view.review() != null) populated.add("review");
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("tool", view.pendingEffect().tool());
        pending.put("policyDecision", view.pendingEffect().policyDecision());
        pending.put("confirmationRequired", view.pendingEffect().confirmationRequired());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("finalStatus", view.status().name());
        result.put("transitions", view.steps().stream().map(step -> step.name()).toList());
        result.put("populatedOutputs", populated);
        result.put("assuranceKeys", view.assuranceResults().keySet().stream().sorted().toList());
        result.put("artifactPaths", artifacts);
        result.put("testsPassed", state.testsPassed);
        result.put("reviewAccepted", state.reviewAccepted);
        result.put("pendingEffect", pending);
        return result;
    }

    private static Map<String, Object> withoutMetadata(Map<String, Object> baseline) {
        Map<String, Object> expected = new LinkedHashMap<>(baseline);
        expected.remove("version");
        expected.remove("baselineCommit");
        return expected;
    }

    private static final class FakeSandbox implements SandboxExecutor {
        @Override public String applyPatch(Path workspace, String taskId, String sourceCommit) { return "applied"; }
        @Override public String checkPatch(Path workspace, String taskId, String sourceCommit) { return "valid"; }
        @Override public String test(Path workspace, String taskId, String sourceCommit) { return "tests-pass"; }
        @Override public String quality(Path workspace, String taskId, String sourceCommit) { return "quality-pass"; }
        @Override public String security(Path workspace, String taskId, String sourceCommit) throws Exception {
            Files.createDirectories(workspace.resolve(".ai-factory"));
            Files.writeString(workspace.resolve(".ai-factory/sbom.cdx.json"), "{}");
            return "security-pass";
        }
    }
}
