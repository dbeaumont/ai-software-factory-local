package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentContractValidator;
import com.example.aifactory.agentcore.AgentLoop;
import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Worker-side execution service consumed by the A2A server transport. */
public final class AgentExecutionWorker {
    private final RoleScopedAgentContext role;
    private final LlmCompletionPort llm;
    private final McpToolPort mcp;

    AgentExecutionWorker(RoleScopedAgentContext role, LlmCompletionPort llm, McpToolPort mcp) {
        this.role = role;
        this.llm = llm;
        this.mcp = mcp;
    }

    public Result execute(Request request) {
        role.requireActiveRole(request.role());
        AgentContractValidator.Context contractContext = new AgentContractValidator.Context(
                request.taskId(), request.attemptId(), request.allowedReferenceIds());
        role.validateInput(request.inputContract(), request.input(), contractContext);
        AgentLoop loop = new AgentLoop(
                messages -> llm.nextTurn(messages, mcp.definitions(), Math.min(request.budget().maxTokens(), 8_192)),
                call -> mcp.call(call.name(), call.arguments()),
                (actor, tool) -> role.allowedTools().contains(tool),
                AgentLoop.SafetyLimits.defaults(), ignored -> { });
        AgentLoop.Result result = loop.run(new AgentLoop.Actor(request.taskId(), role.identity().role(),
                        request.executionMode()), role.systemPrompt(), request.input().toString(), request.budget());
        JsonNode document = role.validateOutput(request.outputContract(), result.finalResult(), contractContext);
        return new Result(document, role.promptFingerprint(), result.turns(), result.tokens(), result.costMicros());
    }

    public record Request(String taskId, String attemptId, String role, String inputContract, JsonNode input,
                          String outputContract, Set<String> allowedReferenceIds, AgentLoop.Budget budget,
                          String executionMode) {
        public Request {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
                    || role == null || role.isBlank() || inputContract == null || inputContract.isBlank()
                    || input == null || outputContract == null || outputContract.isBlank() || budget == null
                    || executionMode == null) throw new IllegalArgumentException("Agent execution request is incomplete");
            allowedReferenceIds = allowedReferenceIds == null ? Set.of() : Set.copyOf(allowedReferenceIds);
        }
    }

    public record Result(JsonNode document, String promptFingerprint, int turns, int tokens, long costMicros) {}
}
