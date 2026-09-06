package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents the removed in-process agent runtime from being restored in the orchestrator module. */
class AgentArchitectureRulesTest {
    private static final List<String> REMOVED_DIRECT_TYPES = List.of(
            "AgentExecutor", "AgentRuntime", "ArchitectureAgents", "CodeAgent", "DeveloperAgent",
            "IndependentReviewerAgent", "PatchRepairAgent", "SecurityAgents", "SupervisorAgent", "TestAgents",
            "AgentContextToolHost", "AgentToolLoop", "PromptService", "ToolPermissionMatrix",
            "DelegationValidator", "AgentActivationGuard", "AgentMetrics", "PlannerResponseFormat");
    private static final List<String> REMOVED_DIRECT_CONFIG = List.of(
            "AgentToolingProperties", "AgentToolEvaluationGuard");

    @Test
    void orchestratorContainsNoInProcessAgentRuntime() {
        Path services = Path.of("src/main/java/com/example/aifactory/service");
        REMOVED_DIRECT_TYPES.forEach(type -> assertThat(services.resolve(type + ".java"))
                .as("legacy direct type %s must stay deleted", type)
                .doesNotExist());
        Path config = Path.of("src/main/java/com/example/aifactory/config");
        REMOVED_DIRECT_CONFIG.forEach(type -> assertThat(config.resolve(type + ".java"))
                .as("legacy direct config %s must stay deleted", type)
                .doesNotExist());
    }

    @Test
    void productionSourcesContainNoDirectAgentConstruction() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java/com/example/aifactory"))) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                String source = read(path);
                REMOVED_DIRECT_TYPES.forEach(type -> assertThat(source)
                        .as("%s must not restore direct agent type %s", path, type)
                        .doesNotContain("new " + type + "(", "import com.example.aifactory.service." + type));
            });
        }
    }

    @Test
    void llmGatewayExposesAvailabilityOnly() {
        String source = read(Path.of("src/main/java/com/example/aifactory/service/LlmGatewayClient.java"));
        assertThat(source).contains("cloudAvailabilityAsync")
                .doesNotContain(" String chat(", "chatDetailed(", "nextToolTurn(", ".block(");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
