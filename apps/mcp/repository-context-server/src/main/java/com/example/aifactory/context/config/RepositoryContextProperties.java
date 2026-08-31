package com.example.aifactory.context.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "ai-factory.context")
public record RepositoryContextProperties(
        Path workspaceRoot,
        int maxFileBytes,
        int maxSearchFiles,
        int maxTreeEntries) {
}

