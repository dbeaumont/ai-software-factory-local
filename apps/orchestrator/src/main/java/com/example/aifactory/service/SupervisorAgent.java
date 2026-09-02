package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Host-owned entry point that constrains the Supervisor to its coordination responsibilities. */
@Component
public final class SupervisorAgent {
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final DelegationValidator delegations;
    private final SupervisorConsolidationGuard consolidationGuard;

    public SupervisorAgent(AgentExecutor runtime, AgentCatalog catalog, DelegationValidator delegations,
                           SupervisorConsolidationGuard consolidationGuard) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.delegations = delegations;
        this.consolidationGuard = consolidationGuard;
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
        AgentRuntime.Result result = runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), "supervisor", outputContract, Set.copyOf(role.tools()),
                request.allowedReferenceIds(), request.untrustedInput(), request.budget()));
        if (operation == Operation.DECOMPOSE) {
            if (request.delegationLimits() == null) {
                throw new IllegalArgumentException("Host delegation limits are required before decomposition");
            }
            delegations.validate(result.document(), request.delegationLimits());
        }
        if (operation == Operation.CONSOLIDATE) {
            consolidationGuard.enforce(result.document(), request.consolidationGates());
        }
        return result;
    }

    enum Operation { DECOMPOSE, CONSOLIDATE, REPLAN }

    public record Request(String taskId, String attemptId, String sourceCommit, String operation,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget,
                          DelegationValidator.Limits delegationLimits,
                          SupervisorConsolidationGuard.GateBundle consolidationGates) {
        public Request {
            if (operation == null || operation.isBlank()) {
                throw new IllegalArgumentException("Supervisor operation is required");
            }
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String operation,
                       Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget,
                       DelegationValidator.Limits delegationLimits) {
            this(taskId, attemptId, sourceCommit, operation, allowedReferenceIds, untrustedInput, budget,
                    delegationLimits, null);
        }
    }
}
