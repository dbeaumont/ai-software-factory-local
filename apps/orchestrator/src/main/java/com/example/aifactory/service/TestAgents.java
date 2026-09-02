package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Host-owned entry point that keeps test design separate from deterministic evidence assessment. */
@Component
public final class TestAgents {
    private static final Map<String, String> OUTPUT_CONTRACTS = Map.of(
            "test-agent", "test-assessment-v1",
            "test-design", "test-strategy-v1",
            "test-evidence", "test-assessment-v1");
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final TestStrategyValidator strategies;

    public TestAgents(AgentExecutor runtime, AgentCatalog catalog, TestStrategyValidator strategies) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.strategies = strategies;
    }

    public AgentRuntime.Result execute(Request request) {
        String contract = OUTPUT_CONTRACTS.get(request.role());
        if (contract == null) throw new IllegalArgumentException("Role is outside the Tests perimeter");
        AgentCatalog.Role role = catalog.require(request.role());
        AgentRuntime.Result result = runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), contract, Set.copyOf(role.tools()),
                request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
        if ("test-design".equals(request.role())) strategies.validate(result.document());
        return result;
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String role,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
