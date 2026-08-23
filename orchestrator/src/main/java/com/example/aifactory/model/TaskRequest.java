package com.example.aifactory.model;

public record TaskRequest(
        String repositoryUrl,
        String baseBranch,
        String requirement,
        Boolean dryRun,
        LlmMode llmMode) {
    public String effectiveBranch() { return baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch; }
    public boolean isDryRun() { return dryRun == null || dryRun; }
    public LlmMode effectiveLlmMode() { return llmMode == null ? LlmMode.LOCAL : llmMode; }
}
