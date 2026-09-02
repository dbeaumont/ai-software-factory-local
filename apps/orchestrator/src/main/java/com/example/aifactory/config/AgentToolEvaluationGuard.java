package com.example.aifactory.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public final class AgentToolEvaluationGuard {
    public AgentToolEvaluationGuard(AgentToolingProperties properties, Environment environment) {
        if (properties.evaluationEnabled() && !environment.matchesProfiles("local", "dev", "test")) {
            throw new IllegalStateException("Agent tool evaluation mode is restricted to local/dev/test profiles");
        }
    }
}
