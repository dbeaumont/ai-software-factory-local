package com.example.aifactory.model;

public record TaskRequest(
        String repositoryUrl,
        String baseBranch,
        String requirement,
        LlmMode llmMode) {
    public String effectiveBranch() { return baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch; }
    public LlmMode effectiveLlmMode() { return llmMode == null ? LlmMode.LOCAL : llmMode; }
}
