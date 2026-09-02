package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Patch Repair entry point bound to one delegation, worktree and numbered repair attempt. */
@Component
public final class PatchRepairAgent {
    private static final List<String> EXACT_BINDINGS = List.of(
            "repair_task_id", "task_id", "attempt_id", "node_id", "code_task_id", "source_commit",
            "worktree_id", "repair_attempt", "scope_digest");
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final MultiAgentContractValidator contracts;

    public PatchRepairAgent(AgentExecutor runtime, AgentCatalog catalog, MultiAgentContractValidator contracts) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.contracts = contracts;
    }

    public AgentRuntime.Result execute(Request request) {
        JsonNode task = contracts.validate("patch-repair-task-v1", request.repairTask(),
                new MultiAgentContractValidator.ContractContext(
                        request.taskId(), request.attemptId(), request.allowedReferenceIds()));
        if (!request.sourceCommit().equals(task.path("source_commit").asText())) {
            throw new IllegalArgumentException("Patch Repair source commit differs from repair task");
        }
        Set<String> outputReferences = new LinkedHashSet<>(request.allowedReferenceIds());
        for (String field : List.of("repair_task_id", "node_id", "code_task_id", "original_proposal_id")) {
            outputReferences.add(task.path(field).asText());
        }
        AgentCatalog.Role role = catalog.require("patch-repair");
        AgentRuntime.Result result = runtime.execute(new AgentRuntime.Invocation(
                request.taskId(), request.attemptId(), request.sourceCommit(), role.name(),
                "patch-repair-hierarchical", "patch-repair-proposal-v1", Set.copyOf(role.tools()),
                Set.copyOf(outputReferences), task.toString(), request.budget()));
        JsonNode proposal = contracts.validate("patch-repair-proposal-v1", result.document(),
                new MultiAgentContractValidator.ContractContext(
                        request.taskId(), request.attemptId(), Set.copyOf(outputReferences)));
        for (String field : EXACT_BINDINGS) {
            if (!task.path(field).equals(proposal.path(field))) {
                throw new SecurityException("Patch repair changed its assigned " + field);
            }
        }
        if (!task.path("original_proposal_id").asText().equals(proposal.path("replaces_proposal_id").asText())) {
            throw new SecurityException("Patch repair changed the proposal it replaces");
        }
        return result;
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String repairTask,
                          Set<String> allowedReferenceIds, AgentToolLoop.Budget budget) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
