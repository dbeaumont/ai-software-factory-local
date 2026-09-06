package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
