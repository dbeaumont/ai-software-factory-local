package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Host-owned entry point for Security coordination, threat modeling and findings analysis. */
@Component
public final class SecurityAgents {
    private static final Set<String> ROLES = Set.of("security-agent", "threat-model", "security-findings");
    private final AgentExecutor runtime;
    private final AgentCatalog catalog;
    private final SecurityFindingsInputValidator findingsValidator;
    private final SecurityDecisionValidator decisionValidator;

    public SecurityAgents(AgentExecutor runtime, AgentCatalog catalog,
                          SecurityFindingsInputValidator findingsValidator,
                          SecurityDecisionValidator decisionValidator) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.findingsValidator = findingsValidator;
        this.decisionValidator = decisionValidator;
    }

    public AgentRuntime.Result execute(Request request) {
        if (!ROLES.contains(request.role())) {
            throw new IllegalArgumentException("Role is outside the Security perimeter");
        }
        AgentCatalog.Role role = catalog.require(request.role());
        String input = request.untrustedInput();
        JsonNode normalizedFindings = null;
        if (Set.of("security-agent", "security-findings").contains(request.role())) {
            normalizedFindings = findingsValidator.validate(request.normalizedFindings(),
                    new SecurityFindingsInputValidator.Context(request.taskId(), request.attemptId(),
                            request.sourceCommit(), request.evidenceReferences()));
            if ("security-findings".equals(request.role())) input = normalizedFindings.toString();
        }
        AgentRuntime.Result result = runtime.execute(new AgentRuntime.Invocation(request.taskId(), request.attemptId(),
                request.sourceCommit(), role.name(), role.name(), "security-assessment-v1",
                Set.copyOf(role.tools()), request.allowedReferenceIds(), input, request.budget()));
        if (normalizedFindings != null) {
            decisionValidator.validate(result.document(), normalizedFindings, request.policyDecisions());
        }
        return result;
    }

    public record Request(String taskId, String attemptId, String sourceCommit, String role,
                          Set<String> allowedReferenceIds, String untrustedInput, AgentToolLoop.Budget budget,
                          String normalizedFindings,
                          Set<SecurityFindingsInputValidator.EvidenceReference> evidenceReferences,
                          Set<SecurityDecisionValidator.PolicyDecision> policyDecisions) {
        public Request {
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
            evidenceReferences = evidenceReferences == null ? Set.of() : Set.copyOf(evidenceReferences);
            policyDecisions = policyDecisions == null ? Set.of() : Set.copyOf(policyDecisions);
        }
    }
}
