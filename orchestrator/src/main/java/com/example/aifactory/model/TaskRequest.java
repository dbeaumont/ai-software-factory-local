package com.example.aifactory.model;

public record TaskRequest(
        String repositoryUrl,
        String baseBranch,
        String requirement,
        Boolean dryRun) {
    public String effectiveBranch() { return baseBranch == null || baseBranch.isBlank() ? "main" : baseBranch; }
    public boolean isDryRun() { return dryRun == null || dryRun; }
}
