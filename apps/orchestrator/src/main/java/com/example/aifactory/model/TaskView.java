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
        java.util.Map<String, Object> assuranceResults,
        java.util.Map<String, Object> evaluationMetrics,
        String review,
        PendingEffect pendingEffect,
        String pullRequestUrl,
        String error,
        List<AgentStep> steps,
        Instant createdAt,
        Instant updatedAt,
        String executionMode,
        String workflowRunId,
        String dagVersion,
        GlobalBudget globalBudget) {

    public record GlobalBudget(Long maxTokens, Long maxCostMicros, Integer maxTurns,
                               long usedTokens, long usedCostMicros, int usedTurns) {}
}
