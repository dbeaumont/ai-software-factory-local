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
    private final A2aSpanLinks spanLinks;

    AgentExecutionWorker(RoleScopedAgentContext role, LlmCompletionPort llm, McpToolPort mcp) {
        this(role, llm, mcp, A2aSpanLinks.disabled());
    }

    AgentExecutionWorker(RoleScopedAgentContext role, LlmCompletionPort llm, McpToolPort mcp,
                         A2aSpanLinks spanLinks) {
        this.role = role;
        this.llm = llm;
        this.mcp = mcp;
        this.spanLinks = spanLinks;
    }

    public Result execute(Request request) {
        return spanLinks.call("ai.factory.a2a.agent.execute", "task-to-agent-execution", request.traceparent(),
                java.util.Map.of("ai_factory.task.id", request.taskId(), "ai_factory.attempt.id", request.attemptId(),
                        "a2a.agent.role", request.role()), () -> executeLinked(request));
    }

    private Result executeLinked(Request request) {
        role.requireActiveRole(request.role());
        AgentContractValidator.Context contractContext = new AgentContractValidator.Context(
                request.taskId(), request.attemptId(), request.allowedReferenceIds());
        role.validateInput(request.inputContract(), request.input(), contractContext);
        AgentLoop loop = new AgentLoop(
                messages -> llm.nextTurn(messages, mcp.definitions(), Math.min(request.budget().maxTokens(), 8_192)),
                call -> mcp.call(call.name(), call.arguments()),
                (actor, tool) -> role.allowedTools().contains(tool),
                AgentLoop.SafetyLimits.defaults(), ignored -> { });
        java.util.concurrent.Callable<AgentLoop.Result> invocation = () -> loop.run(
                new AgentLoop.Actor(request.taskId(), role.identity().role(), request.executionMode()),
                role.systemPrompt(), request.input().toString(), request.budget());
        AgentLoop.Result result;
        try {
            result = request.traceparent() == null ? invocation.call()
                    : new A2aW3cTraceContext(request.traceparent(), request.baggage()).call(invocation);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Agent execution failed", failure);
        }
        JsonNode document = role.validateOutput(request.outputContract(), result.finalResult(), contractContext);
        return new Result(document, role.promptFingerprint(), result.turns(), result.tokens(), result.costMicros());
    }

    public record Request(String taskId, String attemptId, String role, String inputContract, JsonNode input,
                          String outputContract, Set<String> allowedReferenceIds, AgentLoop.Budget budget,
                          String executionMode, String traceparent, String baggage) {
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
