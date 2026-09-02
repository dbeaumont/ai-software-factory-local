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
    private final TestEvidenceValidator evidence;

    public TestAgents(AgentExecutor runtime, AgentCatalog catalog, TestStrategyValidator strategies,
                      TestEvidenceValidator evidence) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.strategies = strategies;
        this.evidence = evidence;
    }

    public AgentRuntime.Result execute(Request request) {
        String contract = OUTPUT_CONTRACTS.get(request.role());
        if (contract == null) throw new IllegalArgumentException("Role is outside the Tests perimeter");
        AgentCatalog.Role role = catalog.require(request.role());
        AgentRuntime.Result result = runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), contract, Set.copyOf(role.tools()),
                request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
        if ("test-design".equals(request.role())) strategies.validate(result.document());
        if ("test-evidence".equals(request.role())) {
            if (!request.sourceCommit().equals(result.document().path("source_commit").asText())
                    || request.expectedPatchDigest() == null
                    || !request.expectedPatchDigest().equals(
                    result.document().path("integrated_patch_digest").asText())) {
                throw new SecurityException("Test Evidence conclusion is outside workflow commit or patch");
            }
            evidence.validate(result.document(), request.executionEvidence());
        }
        return result;
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String role,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget,
                          String expectedPatchDigest, Set<TestEvidenceValidator.ExecutionEvidence> executionEvidence) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
            executionEvidence = executionEvidence == null ? Set.of() : Set.copyOf(executionEvidence);
        }
    }
}
