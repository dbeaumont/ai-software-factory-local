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
            "worktree_id", "repair_attempt", "failure_kind", "failure_digest", "target_paths",
            "conflicting_proposal_ids", "scope_digest");
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final MultiAgentContractValidator contracts;
    private final PatchScopeValidator scopes;

    public PatchRepairAgent(AgentExecutor runtime, AgentCatalog catalog, MultiAgentContractValidator contracts,
                            PatchScopeValidator scopes) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.contracts = contracts;
        this.scopes = scopes;
    }

    public AgentRuntime.Result execute(Request request) {
        JsonNode task = contracts.validate("patch-repair-task-v1", request.repairTask(),
                new MultiAgentContractValidator.ContractContext(
                        request.taskId(), request.attemptId(), request.allowedReferenceIds()));
        if (!request.sourceCommit().equals(task.path("source_commit").asText())) {
            throw new IllegalArgumentException("Patch Repair source commit differs from repair task");
        }
        requireTargetedFailure(task, request.allowedReferenceIds());
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
        scopes.validateRepair(task, proposal);
        return result;
    }

    private static void requireTargetedFailure(JsonNode task, Set<String> allowedReferences) {
        String kind = task.path("failure_kind").asText();
        Set<String> allowedPaths = strings(task.path("allowed_paths"));
        Set<String> targets = strings(task.path("target_paths"));
        if (targets.isEmpty() || targets.stream().anyMatch(target -> allowedPaths.stream().noneMatch(
                allowed -> target.equals(allowed) || target.startsWith(allowed + '/')))) {
            throw new SecurityException("Patch Repair target is outside the original scope");
        }
        Set<String> conflicts = strings(task.path("conflicting_proposal_ids"));
        if (("PATCH_INVALID".equals(kind) && !conflicts.isEmpty())
                || ("PATCH_CONFLICT".equals(kind) && (conflicts.size() != 2
                || !conflicts.contains(task.path("original_proposal_id").asText())
                || !allowedReferences.containsAll(conflicts)))) {
            throw new SecurityException("Patch Repair cause is not a targeted invalid patch or conflict");
        }
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new LinkedHashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Set.copyOf(values);
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String repairTask,
                          Set<String> allowedReferenceIds, AgentToolLoop.Budget budget) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
