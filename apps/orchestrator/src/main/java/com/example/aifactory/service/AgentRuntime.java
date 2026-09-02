package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/** Executes one explicitly configured role and validates its final structured result. */
@Component
public final class AgentRuntime implements AgentExecutor {
    private final PromptService prompts;
    private final LlmGatewayClient llm;
    private final AgentContextToolHost toolHost;
    private final MultiAgentContractValidator contracts;

    public AgentRuntime(PromptService prompts, LlmGatewayClient llm, AgentContextToolHost toolHost,
                        MultiAgentContractValidator contracts) {
        this.prompts = prompts;
        this.llm = llm;
        this.toolHost = toolHost;
        this.contracts = contracts;
    }

    @Override
    public Result execute(Invocation invocation) {
        if (invocation.allowedTools().stream().anyMatch(AgentRuntime::effectfulTool)) {
            throw new IllegalArgumentException("Effectful sandbox, assurance and SCM tools cannot be injected into AgentRuntime");
        }
        List<LlmGatewayClient.ToolDefinition> tools = toolHost.definitions().stream()
                .filter(definition -> invocation.allowedTools().contains(definition.name()))
                .toList();
        Set<String> resolved = tools.stream().map(LlmGatewayClient.ToolDefinition::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!resolved.equals(invocation.allowedTools())) {
            throw new IllegalArgumentException("Invocation contains an unknown or unavailable tool");
        }

        String prompt = prompts.load(invocation.promptName());
        String fingerprint = prompts.fingerprint(invocation.promptName());
        AgentToolLoop loop = new AgentToolLoop(
                messages -> llm.nextToolTurn(messages, tools, Math.min(invocation.budget().maxTokens(), 8_192)),
                toolHost.executor(invocation.taskId(), invocation.sourceCommit(), invocation.role()),
                toolHost.authorization());
        AgentToolLoop.Result result = loop.run(new AgentToolLoop.Actor(invocation.taskId(), invocation.role()),
                prompt, invocation.untrustedInput(), invocation.budget());
        JsonNode document = contracts.validate(invocation.outputContract(), result.finalResult(),
                new MultiAgentContractValidator.ContractContext(invocation.taskId(), invocation.attemptId(),
                        invocation.allowedReferenceIds()));
        return new Result(document, fingerprint, result.turns(), result.tokens(), result.costMicros());
    }

    private static boolean effectfulTool(String name) {
        return name.startsWith("sandbox.") || name.startsWith("assurance.") || name.startsWith("scm.");
    }

    public record Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                             String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                             String untrustedInput, AgentToolLoop.Budget budget) {
        public Invocation {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
                    || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                    || role == null || role.isBlank() || promptName == null || promptName.isBlank()
                    || outputContract == null || outputContract.isBlank() || untrustedInput == null || budget == null) {
                throw new IllegalArgumentException("Agent invocation requires explicit identity, prompt, contract and budget");
            }
            allowedTools = Set.copyOf(allowedTools);
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }

    public record Result(JsonNode document, String promptFingerprint, int turns, int tokens, long costMicros) { }
}
