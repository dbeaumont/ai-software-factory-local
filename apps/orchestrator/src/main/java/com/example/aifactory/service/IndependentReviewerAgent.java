package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Host-owned entry point for the final reviewer, independent from the Supervisor hierarchy. */
@Component
public final class IndependentReviewerAgent {
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final IndependentReviewInputBuilder inputs;

    public IndependentReviewerAgent(AgentExecutor runtime, AgentCatalog catalog,
                                    IndependentReviewInputBuilder inputs) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.inputs = inputs;
    }

    public AgentRuntime.Result execute(Request request) {
        AgentCatalog.Role role = catalog.require("independent-reviewer");
        if (!"workflow".equals(role.parent()) || role.effectful() || !role.mayDelegateTo().isEmpty()) {
            throw new IllegalStateException("Independent Reviewer must remain a read-only workflow child");
        }
        String input = inputs.build(request.bundle(), request.taskId(), request.attemptId(),
                request.sourceCommit()).toString();
        return runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), role.outputContract(), Set.copyOf(role.tools()),
                request.bundle().referenceIds(), input, request.budget()));
    }

    public record Request(String taskId, String attemptId, String sourceCommit,
                          IndependentReviewBundle bundle,
                          AgentToolLoop.Budget budget) {
    }
}
