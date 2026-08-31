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
        int maxQueuedJobs,
        int maxActiveJobsPerTask,
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
        long activeCapacity = (long) maxConcurrentJobs + maxQueuedJobs;
        if (maxConcurrentJobs < 1 || maxConcurrentJobs > 32 || maxQueuedJobs < 0 || maxQueuedJobs > 10_000
                || maxActiveJobsPerTask < 1 || maxActiveJobsPerTask > activeCapacity || maxJobs < activeCapacity
                || jobRetention == null || jobRetention.compareTo(Duration.ofMinutes(1)) < 0
                || jobRetention.compareTo(Duration.ofDays(365)) > 0
                || heartbeatInterval == null || heartbeatInterval.compareTo(Duration.ofSeconds(1)) < 0
                || heartbeatInterval.compareTo(Duration.ofMinutes(5)) > 0
                || maxOutputChars < 1024 || maxOutputChars > 1_048_576
                || maxPatchBytes < 1 || maxPatchBytes > 10_485_760) {
            throw new IllegalArgumentException("invalid sandbox limits");
        }
        if (invalidEnvironmentValue(mavenMirrorUrl) || invalidEnvironmentValue(artifactoryToken)
                || invalidEnvironmentValue(sonarqubeUrl) || invalidEnvironmentValue(sonarToken)) {
            throw new IllegalArgumentException("sandbox environment values must be single-line strings");
        }
    }

    private static boolean invalidEnvironmentValue(String value) {
        return value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0);
    }

}
