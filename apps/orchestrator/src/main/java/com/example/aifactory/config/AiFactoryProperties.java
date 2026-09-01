package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-factory")
public record AiFactoryProperties(
        String llmBaseUrl,
        String llmApiKey,
        String cloudModel,
        boolean cloudEnabled,
        String workspaceRoot,
        String workspaceVolume,
        String sandboxImage,
        String sandboxNetwork,
        String mavenMirrorUrl,
        String artifactoryToken,
        String sonarqubeUrl,
        String sonarToken,
        String promptRoot) {
}
