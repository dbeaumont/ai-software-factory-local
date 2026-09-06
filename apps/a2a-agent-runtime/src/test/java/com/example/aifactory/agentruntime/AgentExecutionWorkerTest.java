package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentExecutionWorkerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void executesOneRoleThroughProviderNeutralPortsAndValidatesBothContracts() throws Exception {
        JsonNode fixtures = fixtures();
        LlmCompletionPort llm = (messages, tools, tokens) -> new AgentLoop.Turn(
                AgentLoop.Stop.FINAL, fixtures.path("patch-proposal-v1").toString(), List.of(), 40, 20, 123);
        McpToolPort mcp = new McpToolPort() {
            @Override public List<LlmCompletionPort.ToolDefinition> definitions() { return List.of(); }
            @Override public String call(String tool, Map<String, Object> arguments) { throw new AssertionError(); }
        };
        AgentExecutionWorker worker = new AgentExecutionWorker(
                RoleScopedAgentContext.load("developer", mapper), llm, mcp);

        AgentExecutionWorker.Result result = worker.execute(request(
                "developer", "code-task-v1", fixtures.path("code-task-v1"), "patch-proposal-v1"));

        assertEquals("proposal-1", result.document().path("proposal_id").asText());
        assertEquals(60, result.tokens());
        assertEquals(123, result.costMicros());
    }

    @Test
    void rejectsASecondRoleAndAnOutputContractOutsideTheRuntimeIdentity() throws Exception {
        JsonNode fixtures = fixtures();
        AgentExecutionWorker worker = new AgentExecutionWorker(RoleScopedAgentContext.load("developer", mapper),
                (messages, tools, tokens) -> new AgentLoop.Turn(AgentLoop.Stop.FINAL,
                        fixtures.path("patch-proposal-v1").toString(), List.of(), 1, 1, 0),
                new NoTools());

        assertThrows(SecurityException.class, () -> worker.execute(request(
                "patch-repair", "code-task-v1", fixtures.path("code-task-v1"), "patch-proposal-v1")));
        assertThrows(SecurityException.class, () -> worker.execute(request(
                "developer", "code-task-v1", fixtures.path("code-task-v1"), "security-assessment-v1")));
    }

    private static AgentExecutionWorker.Request request(String role, String inputContract, JsonNode input,
                                                        String outputContract) {
        return new AgentExecutionWorker.Request("task-1", "attempt-1", role, inputContract, input, outputContract,
                Set.of(), new AgentLoop.Budget(2, Duration.ofSeconds(10), 1_000, 1_000), "HIERARCHICAL_ACTIVE");
    }

    private JsonNode fixtures() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("multiagents/fixtures/golden-contracts-v1.json")) {
            return mapper.readTree(input).path("documents");
        }
    }

    private static final class NoTools implements McpToolPort {
        @Override public List<LlmCompletionPort.ToolDefinition> definitions() { return List.of(); }
        @Override public String call(String tool, Map<String, Object> arguments) { throw new AssertionError(); }
    }
}
