package com.example.aifactory.model;

public record FactoryCapabilities(
        boolean cloudEnabled,
        boolean cloudAvailable,
        String cloudError,
        boolean mcpEnabled,
        boolean repositoryContextMcpAvailable,
        String repositoryContextMcpError,
        boolean sandboxMcpEnabled,
        boolean sandboxMcpAvailable,
        String sandboxMcpError) {
}
