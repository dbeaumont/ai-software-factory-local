package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.workflow.EvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineCompatibilityTest {
    private static final String SOURCE_COMMIT = "a".repeat(40);
    @TempDir Path workspaces;

    @Test
    @SuppressWarnings("unchecked")
    void extractedStepsRecomposeAndMatchTheFrozenVersion02OutputContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(anyList(), nullable(Path.class), any(Duration.class))).thenReturn("cloned", SOURCE_COMMIT);
        RepositoryContextProvider context = (repository, taskId, sourceCommit) -> "context";
        String plan = "{\"status\":\"IMPLEMENTABLE\"}";
        String proposal = "diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new";
        String tester = "{\"coverage_gaps\":[],\"test_cases\":[],\"evidence\":[],\"unverified\":[]}";
        String review = "{\"decision\":\"ACCEPT\",\"findings\":[]}";
        AgentResponseValidator responses = mock(AgentResponseValidator.class);
        when(responses.hasValidPlannerContract(plan)).thenReturn(true);
        when(responses.summarizeReview(review)).thenReturn(
                new AgentResponseValidator.ReviewSummary("ACCEPT", List.of()));
        FakeSandbox sandbox = new FakeSandbox();
        AssuranceGateway assurance = mock(AssuranceGateway.class);
        when(assurance.requireQualityGate(anyString(), anyString(), anyString()))
                .thenReturn(mapper.readTree("{\"verdict\":\"PASSED\"}"));
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        Map<String, EvidenceRepository.RawEvidence> storedContent = new java.util.concurrent.ConcurrentHashMap<>();
        when(evidence.store(any(EvidenceRepository.StoreRequest.class))).thenAnswer(invocation -> {
            EvidenceRepository.StoreRequest request = invocation.getArgument(0);
            EvidenceRepository.StoredEvidence stored = new EvidenceRepository.StoredEvidence(
                    "evidence://" + request.taskId() + '/' + request.attemptId() + '/'
                            + request.type() + '/' + request.digest(),
                    request.digest(), "COMPLETE", request.mediaType(), request.content().length,
                    "INTERNAL", Instant.parse("2030-01-01T00:00:00Z"), Instant.EPOCH);
            storedContent.put(stored.uri(), new EvidenceRepository.RawEvidence(stored.uri(), request.type(),
                    request.digest(), "COMPLETE", "INTERNAL", request.content()));
            return stored;
        });
        when(evidence.read(any(EvidenceRepository.ReadRequest.class))).thenAnswer(invocation ->
                storedContent.get(invocation.<EvidenceRepository.ReadRequest>getArgument(0).uri()));
        PipelineStepService steps = new PipelineStepService(
                new AiFactoryProperties(null, null, "baseline-model", true, workspaces.toString(), null,
                        null, null, null, null, null, null), runner, context, responses,
                sandbox, new PatchIntegrator(sandbox), assurance, mock(ScmDeliveryGateway.class),
                mapper, evidence);
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "https://example.test/repo.git", "main", "change", null));

        recomposePipeline(steps, state, plan, proposal, tester, review);

        Map<String, Object> expected;
        try (var input = Files.newInputStream(Path.of(
                "../../resources/multiagents/baselines/pipeline-v1-output-contract.yaml"))) {
            expected = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        }
        assertThat(expected.get("baselineCommit")).isEqualTo("45e72011a8cc2c81006a5ff7b8b3a3f725db5174");
        assertThat(snapshot(state)).isEqualTo(withoutMetadata(expected));
        verify(evidence, times(9)).store(any(EvidenceRepository.StoreRequest.class));
    }

    private static void recomposePipeline(PipelineStepService steps, TaskState state, String plan,
                                          String proposal, String tester, String review) throws Exception {
        PipelineProjectionEvent.Applier.apply(state, steps.initializeWorkspace(state));
        Path workspace = Path.of(state.workspace);
        state.transition(TaskStatus.CLONING, "Cloning repository");
        apply(state, steps.cloneSource(state, workspace, command(state, "clone", Map.of(
                "repositoryUrl", state.request.repositoryUrl(), "branch", state.request.effectiveBranch()))));
        steps.writeRunMetadata(workspace, state);
        state.transition(TaskStatus.PLANNING, "Planning");
        PipelineProjectionEvent.AgentMetadata metadata = new PipelineProjectionEvent.AgentMetadata(
                Map.of("a2a", "b".repeat(64)), 10, 1, 1);
        steps.prepareAgentInput(state, workspace, "architecture-agent", "PLAN", command(state, "plan", Map.of(
                "requirement", state.request.requirement())), null, 0);
        apply(state, steps.consumeAgentResult(state, workspace, command(state, "plan", Map.of(
                "requirement", state.request.requirement())), "PLAN", plan, metadata, null));
        steps.writeRunMetadata(workspace, state);
        state.transition(TaskStatus.GENERATING_PATCH, "Generating patch");
        var patchCommand = command(state, "generate-patch-candidate", Map.of("plan", state.plan));
        steps.prepareAgentInput(state, workspace, "developer", "GENERATE_PATCH", patchCommand, null, 0);
        apply(state, steps.consumeAgentResult(state, workspace, patchCommand,
                "GENERATE_PATCH", proposal, metadata, null));
        apply(state, steps.validatePatchCandidate(state, workspace,
                command(state, "validate-patch-candidate", Map.of("patch", state.patch))).execution());
        steps.writeRunMetadata(workspace, state);
        state.transition(TaskStatus.APPLYING_PATCH, "Applying patch");
        apply(state, steps.applyPatch(state, workspace, command(state, "apply-patch", Map.of(
                "patch", state.patch))));
        state.transition(TaskStatus.TESTING, "Testing");
        var testCommand = command(state, "test", Map.of("patch", state.patch));
        PipelineStepService.PreparedAgentInput testInput = steps.prepareAgentInput(
                state, workspace, "test-agent", "ASSESS_TESTS", testCommand, null, 0);
        apply(state, steps.consumeAgentResult(state, workspace, testCommand,
                "ASSESS_TESTS", tester, metadata, testInput.supportingArtifact()));
        steps.writeRunMetadata(workspace, state);
        state.transition(TaskStatus.QUALITY_SCANNING, "Quality scanning");
        apply(state, steps.quality(state, workspace, command(state, "quality", Map.of(
                "testSummary", state.testSummary))));
        state.transition(TaskStatus.SECURITY_SCANNING, "Security scanning");
        apply(state, steps.security(state, workspace, command(state, "security", Map.of(
                "qualitySummary", state.qualitySummary))));
        state.transition(TaskStatus.REVIEWING, "Reviewing");
        var reviewCommand = command(state, "review", Map.of(
                "plan", state.plan, "patch", state.patch, "assurance", state.assuranceResults.toString()));
        steps.prepareAgentInput(state, workspace, "independent-reviewer", "REVIEW", reviewCommand, null, 0);
        apply(state, steps.consumeAgentResult(state, workspace, reviewCommand,
                "REVIEW", review, metadata, null));
        steps.writeRunMetadata(workspace, state);
        PipelineProjectionEvent.Applier.apply(state, steps.prepareDelivery(state));
        state.transition(TaskStatus.WAITING_APPROVAL, "Human approval required");
    }

    private static PipelineStepContracts.Command command(TaskState state, String step, Map<String, String> inputs) {
        return PipelineStepContracts.Command.forTask(state, step, inputs);
    }

    private static void apply(TaskState state, PipelineProjectionEvent.StepExecution execution) {
        execution.events().forEach(event -> PipelineProjectionEvent.Applier.apply(state, event));
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
