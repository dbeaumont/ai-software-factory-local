package com.example.aifactory.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "ai-factory.sandbox.compose")
public record ComposeSandboxProperties(
        URI readOnlyRunnerUrl,
        URI writeRunnerUrl,
        URI dependencyRunnerUrl,
        URI qualityRunnerUrl,
        String token,
        String environment) {

    public ComposeSandboxProperties {
        if (readOnlyRunnerUrl == null || writeRunnerUrl == null || dependencyRunnerUrl == null
                || qualityRunnerUrl == null || token == null || token.length() < 32) {
            throw new IllegalArgumentException("compose sandbox runner URLs and a token of at least 32 characters are required");
        }
        if (!"local".equals(environment) && !"dev".equals(environment) && !"test".equals(environment)) {
            throw new IllegalArgumentException("compose sandbox runtime is restricted to local, dev and test environments");
        }
    }
}
