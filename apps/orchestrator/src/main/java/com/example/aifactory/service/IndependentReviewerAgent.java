package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Host-owned entry point for the final reviewer, independent from the Supervisor hierarchy. */
@Component
public final class IndependentReviewerAgent {
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;

    public IndependentReviewerAgent(AgentExecutor runtime, AgentCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    public AgentRuntime.Result execute(Request request) {
        AgentCatalog.Role role = catalog.require("independent-reviewer");
        if (!"workflow".equals(role.parent()) || role.effectful() || !role.mayDelegateTo().isEmpty()) {
            throw new IllegalStateException("Independent Reviewer must remain a read-only workflow child");
        }
        return runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), role.outputContract(), Set.copyOf(role.tools()),
                request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
    }

    public record Request(String taskId, String attemptId, String sourceCommit,
                          Set<String> allowedReferenceIds, String untrustedInput,
                          AgentToolLoop.Budget budget) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
