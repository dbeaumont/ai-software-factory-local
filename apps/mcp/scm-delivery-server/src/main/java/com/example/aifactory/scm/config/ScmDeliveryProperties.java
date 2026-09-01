package com.example.aifactory.scm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "ai-factory.scm")
public record ScmDeliveryProperties(
        String giteaBaseUrl,
        String giteaPublicBaseUrl,
        String giteaUser,
        Path giteaTokenFile,
        Path stateRoot,
        Path repositoryRegistry,
        Path workspaceRoot,
        Path approvalKeyFile) {
    public ScmDeliveryProperties {
        if (giteaBaseUrl == null || giteaBaseUrl.isBlank() || giteaPublicBaseUrl == null
                || giteaPublicBaseUrl.isBlank() || giteaUser == null || giteaUser.isBlank()
                || giteaTokenFile == null || stateRoot == null || repositoryRegistry == null
                || workspaceRoot == null || approvalKeyFile == null) {
            throw new IllegalArgumentException("SCM endpoint, identity, token file and state root are required");
        }
    }
}
