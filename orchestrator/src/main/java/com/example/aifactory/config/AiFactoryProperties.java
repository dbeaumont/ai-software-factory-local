package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-factory")
public record AiFactoryProperties(
        String llmBaseUrl,
        String llmApiKey,
        String localModel,
        String cloudModel,
        boolean cloudEnabled,
        String workspaceRoot,
        String workspaceVolume,
        String sandboxImage,
        String giteaBaseUrl,
        String giteaPublicBaseUrl,
        String giteaToken,
        String giteaUser,
        String promptRoot) {
}
