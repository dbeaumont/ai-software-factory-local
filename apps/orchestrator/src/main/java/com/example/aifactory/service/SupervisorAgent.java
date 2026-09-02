package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Host-owned entry point that constrains the Supervisor to its coordination responsibilities. */
@Component
public final class SupervisorAgent {
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;

    public SupervisorAgent(AgentExecutor runtime, AgentCatalog catalog) {
        this.runtime = runtime;
        this.catalog = catalog;
    }

    public AgentRuntime.Result execute(Request request) {
        Operation operation;
        try {
            operation = Operation.valueOf(request.operation());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Unsupported Supervisor operation " + request.operation(), invalid);
        }
        String outputContract = switch (operation) {
            case DECOMPOSE -> "delegation-plan-v1";
            case CONSOLIDATE, REPLAN -> "supervisor-decision-v1";
        };
        AgentCatalog.Role role = catalog.require("supervisor");
        return runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), "supervisor", outputContract, Set.copyOf(role.tools()),
                request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
    }

    enum Operation { DECOMPOSE, CONSOLIDATE, REPLAN }

    public record Request(String taskId, String attemptId, String sourceCommit, String operation,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget) {
        public Request {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("Supervisor operation is required");
            }
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }
    }
}
