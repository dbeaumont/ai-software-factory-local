package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Host-owned ceilings applied to every model-proposed delegation DAG. */
@ConfigurationProperties(prefix = "ai-factory.delegation")
public record DelegationPolicyProperties(int maxDepth, int maxFanOut) {
    public static final int DEFAULT_MAX_DEPTH = 2;
    public static final int DEFAULT_MAX_FAN_OUT = 4;

    @ConstructorBinding
    public DelegationPolicyProperties {
        if (maxDepth < 1 || maxDepth > DEFAULT_MAX_DEPTH) {
            throw new IllegalArgumentException("Delegation max depth must be between 1 and " + DEFAULT_MAX_DEPTH);
        }
        if (maxFanOut < 1 || maxFanOut > DEFAULT_MAX_FAN_OUT) {
            throw new IllegalArgumentException("Delegation max fan-out must be between 1 and " + DEFAULT_MAX_FAN_OUT);
        }
    }

    public static DelegationPolicyProperties defaults() {
        return new DelegationPolicyProperties(DEFAULT_MAX_DEPTH, DEFAULT_MAX_FAN_OUT);
    }
}
