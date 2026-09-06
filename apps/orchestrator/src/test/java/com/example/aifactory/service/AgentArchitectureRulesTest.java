package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Source-level dependency rules kept independent of a particular architecture-test framework. */
class AgentArchitectureRulesTest {
    private static final List<String> AGENT_IMPLEMENTATIONS = List.of(
            "AgentRuntime", "DeveloperAgent", "PatchRepairAgent", "SupervisorAgent", "ArchitectureAgents",
            "CodeAgent", "TestAgents", "SecurityAgents", "IndependentReviewerAgent");
    private static final List<String> FORBIDDEN_AGENT_DEPENDENCIES = List.of(
            "com.example.aifactory.controller", "com.example.aifactory.workflow",
            "ScmDeliveryGateway", "SandboxGateway", "McpSandboxService", "PatchIntegrator",
            "JdbcTemplate", "DataSource");

    @Test
    void controlPlaneImportsOnlyTheAgentExecutionPort() throws Exception {
        for (String directory : List.of("workflow", "controller", "config")) {
            try (var files = Files.walk(Path.of("src/main/java/com/example/aifactory", directory))) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    String source = read(path);
                    AGENT_IMPLEMENTATIONS.forEach(implementation -> assertThat(source)
                            .as("%s must not depend on concrete %s", path, implementation)
                            .doesNotContain("com.example.aifactory.service." + implementation));
                });
            }
        }
    }

    @Test
    void agentImplementationsCannotReachControllersProjectionsOrEffectClients() {
        AGENT_IMPLEMENTATIONS.forEach(implementation -> {
            Path path = Path.of("src/main/java/com/example/aifactory/service", implementation + ".java");
            String source = read(path);
            FORBIDDEN_AGENT_DEPENDENCIES.forEach(dependency -> assertThat(source)
                    .as("%s must not depend on %s", implementation, dependency).doesNotContain(dependency));
        });
    }

    @Test
    void productionSpringGraphExcludesDirectAgentImplementations() {
        AGENT_IMPLEMENTATIONS.forEach(implementation -> {
            Path path = Path.of("src/main/java/com/example/aifactory/service", implementation + ".java");
            assertThat(read(path)).as("%s must not be Spring-managed", implementation)
                    .doesNotContain("@Component", "@Service", "@Bean");
        });
        String activities = read(Path.of(
                "src/main/java/com/example/aifactory/workflow/temporal/DurableExecutionActivitiesImpl.java"));
        assertThat(activities).doesNotContain("AgentExecutor", "invokeAgent(", "InvokeAgent");
    }

    private static String read(Path path) {
        try { return Files.readString(path, StandardCharsets.UTF_8); }
        catch (java.io.IOException exception) { throw new IllegalStateException(exception); }
    }
}
