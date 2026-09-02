package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit opt-in policy for sensitive content in logs, metrics and traces. */
@ConfigurationProperties(prefix = "ai-factory.observability.content")
public record ObservabilityContentProperties(boolean prompts, boolean results, boolean evidence) {
    public static ObservabilityContentProperties disabled() {
        return new ObservabilityContentProperties(false, false, false);
    }

    public boolean enabled(ContentKind kind) {
        if (kind == null) throw new IllegalArgumentException("Content kind is required");
        return switch (kind) {
            case PROMPT -> prompts;
            case RESULT -> results;
            case EVIDENCE -> evidence;
        };
    }

    public enum ContentKind { PROMPT, RESULT, EVIDENCE }
}
