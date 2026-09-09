package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentExecutionActivitiesImplTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void materializesTheBoundEvidenceInputAndReturnsAValidatedArtifact() throws Exception {
        JsonNode documents = fixtures();
        LlmCompletionPort llm = (messages, tools, tokens) -> new AgentLoop.Turn(
                AgentLoop.Stop.FINAL, documents.path("patch-proposal-v1").toString(), List.of(), 10, 5, 42);
        McpToolPort noTools = new McpToolPort() {
            @Override public List<LlmCompletionPort.ToolDefinition> definitions() { return List.of(); }
            @Override public String call(String tool, Map<String, Object> arguments) { throw new AssertionError(); }
        };
        AgentExecutionWorker worker = new AgentExecutionWorker(
                RoleScopedAgentContext.load("developer", mapper), llm, noTools);
        AgentExecutionActivitiesImpl activities = new AgentExecutionActivitiesImpl(worker,
                (task, attempt, reference, maximum) -> {
                    assertThat(task).isEqualTo("task-1");
                    assertThat(attempt).isEqualTo("attempt-1");
                    assertThat(reference.referenceId()).isEqualTo("code-task-1");
                    assertThat(maximum).isEqualTo(1_048_576);
                    return documents.path("code-task-v1");
                }, mapper);

        AgentExecutionActivities.Result result = activities.execute(new AgentExecutionActivities.Command(
                "task-1", "developer", "developer.code-task-v1", envelope(), null, null));

        assertThat(result.attemptId()).isEqualTo("attempt-1");
        assertThat(result.outputContract()).isEqualTo("patch-proposal-v1");
        assertThat(result.allowedReferenceIds()).containsExactly("code-task-1");
        assertThat(result.artifactDigest()).matches("[0-9a-f]{64}");
        assertThat(mapper.readTree(java.util.Base64.getDecoder().decode(result.artifactContentBase64()))
                .path("proposal_id").asText()).isEqualTo("proposal-1");
    }

    @Test
    void rejectsAnEnvelopeThatChangesTheAdmittedRoleOrReferenceBinding() {
        AgentExecutionWorker unused = org.mockito.Mockito.mock(AgentExecutionWorker.class);
        AgentExecutionActivitiesImpl activities = new AgentExecutionActivitiesImpl(unused,
                (task, attempt, reference, maximum) -> { throw new AssertionError(); }, mapper);

        assertThatThrownBy(() -> activities.execute(new AgentExecutionActivities.Command(
                "task-1", "architecture-agent", "developer.code-task-v1", envelope(), null, null)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> activities.execute(new AgentExecutionActivities.Command(
                "other-task", "developer", "developer.code-task-v1", envelope(), null, null)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void materializesOnlyThePrimaryInputAndAdmitsAdditionalEvidenceReferences() throws Exception {
        JsonNode documents = fixtures();
        LlmCompletionPort llm = (messages, tools, tokens) -> new AgentLoop.Turn(
                AgentLoop.Stop.FINAL, documents.path("patch-proposal-v1").toString(), List.of(), 10, 5, 42);
        McpToolPort noTools = new McpToolPort() {
            @Override public List<LlmCompletionPort.ToolDefinition> definitions() { return List.of(); }
            @Override public String call(String tool, Map<String, Object> arguments) { throw new AssertionError(); }
        };
        AgentExecutionActivitiesImpl activities = new AgentExecutionActivitiesImpl(new AgentExecutionWorker(
                RoleScopedAgentContext.load("developer", mapper), llm, noTools),
                (task, attempt, reference, maximum) -> {
                    assertThat(reference.referenceId()).isEqualTo("code-task-1");
                    return documents.path("code-task-v1");
                }, mapper);
        JsonNode envelope = mapper.readTree(envelope());
        JsonNode secondary = envelope.path("input_references").get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) secondary)
                .put("reference_id", "supporting-evidence-1")
                .put("uri", "evidence://task-1/attempt-1/supporting/evidence.json");
        ((ArrayNode) envelope.path("input_references")).add(secondary);
        ((ArrayNode) envelope.path("constraints").path("allowed_reference_ids"))
                .add("supporting-evidence-1");

        AgentExecutionActivities.Result result = activities.execute(new AgentExecutionActivities.Command(
                "task-1", "developer", "developer.code-task-v1", envelope.toString(), null, null));

        assertThat(result.allowedReferenceIds()).containsExactlyInAnyOrder(
                "code-task-1", "supporting-evidence-1");
    }

    @Test
    void materializesEveryAdmittedReferenceForIndependentReview() throws Exception {
        JsonNode documents = fixtures();
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        LlmCompletionPort llm = (messages, tools, tokens) -> {
            assertThat(tools).isEmpty();
            assertThat(messages.get(1).content()).contains("admitted_evidence", "result-1");
            return new AgentLoop.Turn(AgentLoop.Stop.FINAL,
                    documents.path("independent-review-v1").toString(), List.of(), 10, 5, 42);
        };
        McpToolPort noTools = new McpToolPort() {
            @Override public List<LlmCompletionPort.ToolDefinition> definitions() { return List.of(); }
            @Override public String call(String tool, Map<String, Object> arguments) { throw new AssertionError(); }
        };
        AgentExecutionActivitiesImpl activities = new AgentExecutionActivitiesImpl(new AgentExecutionWorker(
                RoleScopedAgentContext.load("independent-reviewer", mapper), llm, noTools),
                (task, attempt, reference, maximum) -> {
                    reads.incrementAndGet();
                    return "evidence-manifest-v1".equals(reference.contract())
                            ? documents.path("evidence-manifest-v1") : documents.path("specialist-result-v1");
                }, mapper);
        JsonNode envelope = mapper.readTree(envelope());
        ((tools.jackson.databind.node.ObjectNode) envelope)
                .put("target_role", "independent-reviewer")
                .put("skill_id", "independent-reviewer.evidence-manifest-v1");
        ((tools.jackson.databind.node.ObjectNode) envelope.path("constraints"))
                .put("expected_output_contract", "independent-review-v1");
        JsonNode primary = envelope.path("input_references").get(0);
        ((tools.jackson.databind.node.ObjectNode) primary)
                .put("reference_id", "d".repeat(64))
                .put("uri", "evidence://task-1/attempt-1/manifest/" + "b".repeat(64))
                .put("contract", "evidence-manifest-v1");
        JsonNode secondary = primary.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) secondary)
                .put("reference_id", "result-1")
                .put("uri", "evidence://task-1/attempt-1/review/" + "b".repeat(64))
                .put("contract", "specialist-result-v1");
        ((ArrayNode) envelope.path("input_references")).add(secondary);
        ArrayNode allowed = (ArrayNode) envelope.path("constraints").path("allowed_reference_ids");
        allowed.set(0, mapper.getNodeFactory().textNode("d".repeat(64)));
        allowed.add("result-1");

        activities.execute(new AgentExecutionActivities.Command(
                "task-1", "independent-reviewer", "independent-reviewer.evidence-manifest-v1",
                envelope.toString(), null, null));

        assertThat(reads).hasValue(2);
    }

    private String envelope() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("a2a/fixtures/a2a-envelope-v1.json")) {
            return mapper.readTree(input).toString();
        }
    }

    private JsonNode fixtures() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("multiagents/fixtures/golden-contracts-v1.json")) {
            return mapper.readTree(input).path("documents");
        }
    }
}
