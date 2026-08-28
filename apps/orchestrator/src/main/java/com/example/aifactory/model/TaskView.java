package com.example.aifactory.model;

import java.time.Instant;
import java.util.List;

public record TaskView(
        String id,
        String ticketNumber,
        TaskStatus status,
        String repositoryUrl,
        String baseBranch,
        String requirement,
        LlmMode llmMode,
        String workspace,
        String sourceCommit,
        String model,
        java.util.Map<String, String> promptFingerprints,
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
