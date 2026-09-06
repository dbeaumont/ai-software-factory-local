package com.example.aifactory.agentcore;

import tools.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.Set;

/** Framework-neutral composition of catalog, prompt, loop and contract validation. */
public final class AgentEngine {
    private final AgentCatalog catalog;
    private final PromptRepository prompts;
    private final AgentContractValidator contracts;
    private final AgentLoop.Model model;
    private final AgentLoop.ToolExecutor tools;
    private final AgentLoop.UsageSink usage;

    public AgentEngine(AgentCatalog catalog, PromptRepository prompts, AgentContractValidator contracts,
                       AgentLoop.Model model, AgentLoop.ToolExecutor tools, AgentLoop.UsageSink usage) {
        this.catalog = Objects.requireNonNull(catalog);
        this.prompts = Objects.requireNonNull(prompts);
        this.contracts = Objects.requireNonNull(contracts);
        this.model = Objects.requireNonNull(model);
        this.tools = Objects.requireNonNull(tools);
        this.usage = Objects.requireNonNull(usage);
    }

    public Result execute(Invocation invocation) {
        AgentCatalog.Role role = catalog.require(invocation.role());
        if (!role.isAgent()) throw new IllegalArgumentException("Control-plane role cannot execute as agent");
        if (!role.outputContract().equals(invocation.outputContract())) {
            throw new IllegalArgumentException("Output contract differs from catalog");
        }
        if (!role.tools().containsAll(invocation.allowedTools())) {
            throw new IllegalArgumentException("Invocation grants a tool outside the role catalog");
        }
        AgentLoop loop = new AgentLoop(model, tools,
                (actor, tool) -> invocation.allowedTools().contains(tool),
                AgentLoop.SafetyLimits.defaults(), usage);
        AgentLoop.Result result = loop.run(new AgentLoop.Actor(invocation.taskId(), invocation.role(),
                        invocation.executionMode()), prompts.load(invocation.promptName()),
                invocation.untrustedInput(), invocation.budget());
        JsonNode document = contracts.validate(invocation.outputContract(), result.finalResult(),
                new AgentContractValidator.Context(invocation.taskId(), invocation.attemptId(),
                        invocation.allowedReferenceIds()));
        requireOptionalBinding(document, "role", invocation.role());
        requireOptionalBinding(document, "source_commit", invocation.sourceCommit());
        return new Result(document, prompts.fingerprint(invocation.promptName()), result.turns(),
                result.tokens(), result.costMicros(), result.stopCondition());
    }

    private static void requireOptionalBinding(JsonNode document, String field, String expected) {
        JsonNode actual = document.path(field);
        if (!actual.isMissingNode() && (!actual.isTextual() || !expected.equals(actual.asText()))) {
            throw new IllegalArgumentException(field + " is not bound to the host invocation");
        }
    }

    public record Invocation(String taskId, String attemptId, String sourceCommit, String role,
                             String promptName, String outputContract, Set<String> allowedTools,
                             Set<String> allowedReferenceIds, String untrustedInput, AgentLoop.Budget budget,
                             String executionMode, ExecutionIdentity identity) {
        public Invocation {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
                    || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                    || role == null || role.isBlank() || promptName == null || promptName.isBlank()
                    || outputContract == null || outputContract.isBlank() || untrustedInput == null
                    || budget == null || identity == null) {
                throw new IllegalArgumentException("Agent invocation is incomplete");
            }
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            allowedReferenceIds = allowedReferenceIds == null ? Set.of() : Set.copyOf(allowedReferenceIds);
        }
    }
    public record Result(JsonNode document, String promptFingerprint, int turns, int tokens,
                         long costMicros, AgentLoop.StopCondition stopCondition) {}
}
