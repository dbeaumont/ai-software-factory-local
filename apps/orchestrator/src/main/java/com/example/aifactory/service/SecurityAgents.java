package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Set;

/** Host-owned entry point for Security coordination, threat modeling and findings analysis. */
@Component
public final class SecurityAgents {
    private static final Set<String> ROLES = Set.of("security-agent", "threat-model", "security-findings");
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final SecurityFindingsInputValidator findingsValidator;

    public SecurityAgents(AgentExecutor runtime, AgentCatalog catalog,
                          SecurityFindingsInputValidator findingsValidator) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.findingsValidator = findingsValidator;
    }

    public AgentRuntime.Result execute(Request request) {
        if (!ROLES.contains(request.role())) {
            throw new IllegalArgumentException("Role is outside the Security perimeter");
        }
        AgentCatalog.Role role = catalog.require(request.role());
        String input = request.untrustedInput();
        if ("security-findings".equals(request.role())) {
            input = findingsValidator.validate(request.normalizedFindings(),
                    new SecurityFindingsInputValidator.Context(request.taskId(), request.attemptId(),
                            request.sourceCommit(), request.evidenceReferences())).toString();
        }
        return runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), "security-assessment-v1",
                Set.copyOf(role.tools()), request.allowedReferenceIds(), input, request.budget()));
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String role,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget,
                          String normalizedFindings,
                          Set<SecurityFindingsInputValidator.EvidenceReference> evidenceReferences) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
            evidenceReferences = evidenceReferences == null ? Set.of() : Set.copyOf(evidenceReferences);
        }
    }
}
