package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.Set;

/** Hierarchical Developer entry point bound to one validated code scope and one patch proposal contract. */
@Component
public final class DeveloperAgent {
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final MultiAgentContractValidator contracts;
    private final PatchScopeValidator scopes;

    public DeveloperAgent(AgentExecutor runtime, AgentCatalog catalog, MultiAgentContractValidator contracts,
                          PatchScopeValidator scopes) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.contracts = contracts;
        this.scopes = scopes;
    }

    public AgentRuntime.Result execute(Request request) {
        MultiAgentContractValidator.ContractContext inputContext =
                new MultiAgentContractValidator.ContractContext(request.taskId(), request.attemptId(),
                        request.allowedReferenceIds());
        JsonNode task = contracts.validate("code-task-v1", request.codeTask(), inputContext);
        if (!request.sourceCommit().equals(task.path("source_commit").asText())) {
            throw new IllegalArgumentException("Developer source commit differs from code task");
        }
        Set<String> outputReferences = new LinkedHashSet<>(request.allowedReferenceIds());
        outputReferences.add(task.path("code_task_id").asText());
        outputReferences.add(task.path("node_id").asText());
        AgentCatalog.Role role = catalog.require("developer");
        AgentRuntime.Result result = runtime.execute(new AgentRuntime.Invocation(
                request.taskId(), request.attemptId(), request.sourceCommit(), role.name(),
                "developer-hierarchical", "patch-proposal-v1", Set.copyOf(role.tools()),
                Set.copyOf(outputReferences), task.toString(), request.budget()));
        JsonNode proposal = contracts.validate("patch-proposal-v1", result.document(),
                new MultiAgentContractValidator.ContractContext(
                        request.taskId(), request.attemptId(), Set.copyOf(outputReferences)));
        if (!task.path("code_task_id").asText().equals(proposal.path("code_task_id").asText())
                || !task.path("node_id").asText().equals(proposal.path("node_id").asText())
                || !task.path("source_commit").asText().equals(proposal.path("source_commit").asText())
                || !task.path("worktree_id").asText().equals(proposal.path("worktree_id").asText())
                || !task.path("scope_digest").asText().equals(proposal.path("scope_digest").asText())) {
            throw new SecurityException("Patch proposal is not bound to the assigned code task and scope");
        }
        scopes.validateDeveloper(task, proposal);
        return result;
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String codeTask,
                          Set<String> allowedReferenceIds, AgentToolLoop.Budget budget) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
