package com.example.aifactory.service;

import com.example.aifactory.config.AgentToolingProperties;
import org.springframework.stereotype.Component;

/** Production gate between configured qualification and every hierarchical agent invocation. */
@Component
public final class AgentActivationGuard {
    private final AgentToolingProperties properties;
    private final boolean testBypass;

    public AgentActivationGuard(AgentToolingProperties properties) {
        this(properties, false);
    }

    private AgentActivationGuard(AgentToolingProperties properties, boolean testBypass) {
        this.properties = properties;
        this.testBypass = testBypass;
    }

    static AgentActivationGuard allowAllForTests() {
        return new AgentActivationGuard(null, true);
    }

    public void requireAllowed(String role, String executionMode) {
        if (testBypass) return;
        if ("HIERARCHICAL_SHADOW".equals(executionMode)) {
            if (!properties.evaluationEnabled() || !properties.evaluationRoles().contains(role)) {
                throw new SecurityException("Hierarchical role is not enabled for shadow evaluation");
            }
            return;
        }
        if (!"QUALIFIED".equals(properties.qualificationVerdict())
                || !properties.enabledRoles().contains(role)) {
            throw new SecurityException("Hierarchical role is disabled until qualification");
        }
    }
}
