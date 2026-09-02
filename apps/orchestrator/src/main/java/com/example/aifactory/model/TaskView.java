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
        GlobalBudget globalBudget,
        List<DelegationView> delegations,
        List<ArtifactView> artifacts,
        List<ContradictionView> contradictions,
        List<DecisionView> decisions,
        List<HumanActionView> humanActions) {

    public record GlobalBudget(Long maxTokens, Long maxCostMicros, Integer maxTurns,
                               long usedTokens, long usedCostMicros, int usedTurns) {}

    public record DelegationView(String delegationId, String parentDelegationId, String role,
                                 List<String> dependsOn, String status, String stopReason,
                                 Long durationMillis, Integer turns, Long tokens, Long costMicros,
                                 List<String> toolsUsed) {
        public DelegationView {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            durationMillis = durationMillis == null ? 0L : durationMillis;
            turns = turns == null ? 0 : turns;
            tokens = tokens == null ? 0L : tokens;
            costMicros = costMicros == null ? 0L : costMicros;
            toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
        }

        public DelegationView(String delegationId, String parentDelegationId, String role,
                              List<String> dependsOn, String status, String stopReason) {
            this(delegationId, parentDelegationId, role, dependsOn, status, stopReason,
                    0L, 0, 0L, 0L, List.of());
        }
    }

    public record ArtifactView(String artifactId, String type, String status, String classification,
                               String uri, String digest, long sizeBytes) {}

    public record ContradictionView(String contradictionId, String subject, String type,
                                    String severity, String status) {}

    public record DecisionView(String decisionId, String contradictionId, String ruleId,
                               String decision, String author) {}

    public record HumanActionView(String requestId, String contradictionId, String domain,
                                  String question, String objectDigest, String status) {}
}
