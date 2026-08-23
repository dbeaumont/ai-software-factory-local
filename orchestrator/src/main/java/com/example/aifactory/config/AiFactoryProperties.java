package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-factory")
public record AiFactoryProperties(
        String ollamaBaseUrl,
        String ollamaModel,
        String workspaceRoot,
        String sandboxImage,
        String giteaBaseUrl,
        String giteaToken,
        String giteaUser,
        String promptRoot) {
}
