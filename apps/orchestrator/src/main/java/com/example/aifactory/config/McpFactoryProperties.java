package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "ai-factory.mcp")
public record McpFactoryProperties(
        boolean enabled,
        ContextMode repositoryContextMode,
        String repositoryContextServerName,
        boolean sandboxEnabled,
        SandboxMode sandboxMode,
        String sandboxServerName,
        Duration sandboxPollInterval,
        Duration sandboxPollTimeout,
        Set<String> repositoryContextActiveRoles,
        Set<String> sandboxActiveOperations) {

    public static final Set<String> ALL_SANDBOX_OPERATIONS = Set.of(
            "validate_patch", "apply_patch", "run_tests", "run_quality", "run_security");

    @ConstructorBinding
    public McpFactoryProperties {
        repositoryContextActiveRoles = repositoryContextActiveRoles == null
                ? Set.of() : Set.copyOf(repositoryContextActiveRoles);
        sandboxActiveOperations = sandboxActiveOperations == null
                ? ALL_SANDBOX_OPERATIONS : Set.copyOf(sandboxActiveOperations);
    }

    public McpFactoryProperties(boolean enabled, ContextMode repositoryContextMode, String repositoryContextServerName) {
        this(enabled, repositoryContextMode, repositoryContextServerName, false, SandboxMode.DIRECT,
                "sandbox-execution-mcp", Duration.ofMillis(250), Duration.ofMinutes(20),
                Set.of("planner", "developer", "patch-repair"), ALL_SANDBOX_OPERATIONS);
    }

    public McpFactoryProperties(boolean enabled, ContextMode repositoryContextMode, String repositoryContextServerName,
                                boolean sandboxEnabled, SandboxMode sandboxMode, String sandboxServerName,
                                Duration sandboxPollInterval, Duration sandboxPollTimeout) {
        this(enabled, repositoryContextMode, repositoryContextServerName, sandboxEnabled, sandboxMode,
                sandboxServerName, sandboxPollInterval, sandboxPollTimeout,
                Set.of("planner", "developer", "patch-repair"), ALL_SANDBOX_OPERATIONS);
    }

    public boolean sandboxOperationActive(String operation) {
        return sandboxActiveOperations.contains(operation);
    }

    public enum ContextMode {
        DIRECT,
        MCP_SHADOW,
        MCP_ACTIVE
    }

    public enum SandboxMode {
        DIRECT,
        MCP_SHADOW,
        MCP_ACTIVE
    }
}
