package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Host-owned entry point for the Architecture agent and its two read-only specialists. */
@Component
public final class ArchitectureAgents {
    private static final Map<String, String> OUTPUT_CONTRACTS = Map.of(
            "architecture-agent", "architecture-assessment-v1",
            "impact-analysis", "specialist-result-v1",
            "dependencies-contracts", "specialist-result-v1");

    private final AgentExecutor runtime;
    private final AgentCatalog catalog;

    public ArchitectureAgents(AgentExecutor runtime, AgentCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    public AgentRuntime.Result execute(Request request) {
        String outputContract = OUTPUT_CONTRACTS.get(request.role());
        if (outputContract == null) {
            throw new IllegalArgumentException("Role is outside the Architecture perimeter: " + request.role());
        }
        AgentCatalog.Role role = catalog.require(request.role());
        if (role.tools().stream().anyMatch(tool -> !tool.startsWith("context."))) {
            throw new IllegalStateException("Architecture role is not read-only: " + role.name());
        }
        return runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), outputContract, Set.copyOf(role.tools()),
                request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String role,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget) {
        public Request {
            if (role == null || role.isBlank()) throw new IllegalArgumentException("Architecture role is required");
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
