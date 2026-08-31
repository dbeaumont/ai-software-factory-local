package com.example.aifactory.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "ai-factory.sandbox")
public record SandboxExecutionProperties(
        Path workspaceRoot,
        Path stateRoot,
        String workspaceVolume,
        String image,
        String network,
        int maxConcurrentJobs,
        int maxJobs,
        Duration jobRetention,
        Duration heartbeatInterval,
        int maxOutputChars,
        long maxPatchBytes,
        String mavenMirrorUrl,
        String artifactoryToken,
        String sonarqubeUrl,
        String sonarToken) {

    public SandboxExecutionProperties {
        if (workspaceRoot == null || stateRoot == null || workspaceVolume == null || workspaceVolume.isBlank()
                || image == null || image.isBlank() || network == null || network.isBlank()) {
            throw new IllegalArgumentException("sandbox workspace, volume, image and network are required");
        }
        if (maxConcurrentJobs < 1 || maxConcurrentJobs > 32 || maxJobs < maxConcurrentJobs
                || jobRetention == null || jobRetention.compareTo(Duration.ofMinutes(1)) < 0
                || jobRetention.compareTo(Duration.ofDays(365)) > 0
                || heartbeatInterval == null || heartbeatInterval.compareTo(Duration.ofSeconds(1)) < 0
                || heartbeatInterval.compareTo(Duration.ofMinutes(5)) > 0
                || maxOutputChars < 1024 || maxOutputChars > 1_048_576
                || maxPatchBytes < 1 || maxPatchBytes > 10_485_760) {
            throw new IllegalArgumentException("invalid sandbox limits");
        }
    }
}
