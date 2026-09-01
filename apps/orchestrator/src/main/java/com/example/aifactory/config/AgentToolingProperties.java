package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Set;

@ConfigurationProperties(prefix = "ai-factory.agent-tools")
public record AgentToolingProperties(Set<String> enabledRoles, String qualificationVerdict,
                                     int pairedCases, boolean securityPassed) {
    private static final Set<String> KNOWN_ROLES = Set.of("planner", "reviewer");

    @ConstructorBinding
    public AgentToolingProperties {
        enabledRoles = enabledRoles == null ? Set.of() : Set.copyOf(enabledRoles);
        qualificationVerdict = qualificationVerdict == null ? "INCOMPLETE" : qualificationVerdict;
        if (!KNOWN_ROLES.containsAll(enabledRoles)) {
            throw new IllegalArgumentException("Unknown agent tool role requested");
        }
        if (!enabledRoles.isEmpty()
                && (!"QUALIFIED".equals(qualificationVerdict) || pairedCases < 20 || !securityPassed)) {
            throw new IllegalArgumentException("Agent tool roles require a qualified A/B and security campaign");
        }
    }

    public boolean enabledFor(String role) {
        return enabledRoles.contains(role);
    }
}
