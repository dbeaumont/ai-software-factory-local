package com.example.aifactory.model;

import java.time.Instant;
import java.util.List;

public record TaskView(
        String id,
        TaskStatus status,
        String repositoryUrl,
        String baseBranch,
        String requirement,
        boolean dryRun,
        LlmMode llmMode,
        String workspace,
        String plan,
        String patch,
        String testSummary,
        String qualitySummary,
        String securitySummary,
        String review,
        String pullRequestUrl,
        String error,
        List<AgentStep> steps,
        Instant createdAt,
        Instant updatedAt) {
}
