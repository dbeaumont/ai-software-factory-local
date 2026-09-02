package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Set;

@ConfigurationProperties(prefix = "ai-factory.agent-tools")
public record AgentToolingProperties(Set<String> enabledRoles, String qualificationVerdict,
                                     int pairedCases, boolean securityPassed,
                                     boolean evaluationEnabled, Set<String> evaluationRoles) {
    private static final int MINIMUM_PAIRED_CASES = 36;
    private static final Set<String> KNOWN_ROLES = Set.of("planner", "reviewer", "tester",
            "supervisor", "architecture-agent", "impact-analysis", "dependencies-contracts",
            "code-agent", "developer", "patch-repair", "test-agent", "test-design", "test-evidence",
            "security-agent", "threat-model", "security-findings", "independent-reviewer");

    @ConstructorBinding
    public AgentToolingProperties {
        enabledRoles = enabledRoles == null ? Set.of() : Set.copyOf(enabledRoles);
        evaluationRoles = evaluationRoles == null ? Set.of() : Set.copyOf(evaluationRoles);
        qualificationVerdict = qualificationVerdict == null ? "INCOMPLETE" : qualificationVerdict;
        if (!KNOWN_ROLES.containsAll(enabledRoles)) {
            throw new IllegalArgumentException("Unknown agent tool role requested");
        }
        if (!KNOWN_ROLES.containsAll(evaluationRoles) || (!evaluationEnabled && !evaluationRoles.isEmpty())) {
            throw new IllegalArgumentException("Invalid agent tool evaluation roles");
        }
        if (!enabledRoles.isEmpty()
                && (!"QUALIFIED".equals(qualificationVerdict)
                || pairedCases < MINIMUM_PAIRED_CASES || !securityPassed)) {
            throw new IllegalArgumentException("Agent tool roles require a qualified A/B and security campaign");
        }
    }

    public boolean enabledFor(String role) {
        return enabledRoles.contains(role) || (evaluationEnabled && evaluationRoles.contains(role));
    }
}
