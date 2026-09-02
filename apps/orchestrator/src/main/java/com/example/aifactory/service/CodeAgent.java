package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Logical Code coordinator. Worktree creation, patch application and integration remain workflow effects. */
@Component
public final class CodeAgent {
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;

    public CodeAgent(AgentExecutor runtime, AgentCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    public AgentRuntime.Result coordinate(Request request) {
        AgentCatalog.Role role = catalog.require("code-agent");
        return runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), "integration-proposal-v1",
                Set.copyOf(role.tools()), request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
    }

    public record Request(String taskId, String attemptId, String sourceCommit,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
