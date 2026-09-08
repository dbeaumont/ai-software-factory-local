package com.example.aifactory.model;

public record TaskRequest(
        String repositoryUrl,
        String baseBranch,
        String requirement,
        LlmMode llmMode,
        TaskRoutingFacts routingFacts) {
    public TaskRequest(String repositoryUrl, String baseBranch, String requirement, LlmMode llmMode) {
        this(repositoryUrl, baseBranch, requirement, llmMode, TaskRoutingFacts.qualifiedLowRiskFixture());
    }

    public String effectiveBranch() { return baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch; }
    public LlmMode effectiveLlmMode() { return LlmMode.CLOUD; }
}
