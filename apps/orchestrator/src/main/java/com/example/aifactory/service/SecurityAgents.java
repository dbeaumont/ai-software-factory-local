package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Host-owned entry point for Security coordination, threat modeling and findings analysis. */
@Component
public final class SecurityAgents {
    private static final Set<String> ROLES = Set.of("security-agent", "threat-model", "security-findings");
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;

    public SecurityAgents(AgentExecutor runtime, AgentCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    public AgentRuntime.Result execute(Request request) {
        if (!ROLES.contains(request.role())) {
            throw new IllegalArgumentException("Role is outside the Security perimeter");
        }
        AgentCatalog.Role role = catalog.require(request.role());
        return runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), "security-assessment-v1",
                Set.copyOf(role.tools()), request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String role,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
