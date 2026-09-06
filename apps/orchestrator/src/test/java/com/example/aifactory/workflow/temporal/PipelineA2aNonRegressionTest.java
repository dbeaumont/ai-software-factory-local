package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents the legacy PIPELINE business mode from becoming a direct-agent transport selector. */
class PipelineA2aNonRegressionTest {
    private static final List<String> CONTROL_SOURCES = List.of(
            "workflow/temporal/TemporalWorkflowCoordinator.java",
            "workflow/temporal/SoftwareFactoryExecutionWorkflowV1Impl.java",
            "workflow/temporal/SoftwareFactoryWorkflowImpl.java",
            "workflow/temporal/PipelineExecutionActivitiesImpl.java",
            "service/PipelineStepService.java",
            "service/TaskService.java");
    private static final List<String> DIRECT_AGENT_SYMBOLS = List.of(
            "AgentRuntime", "AgentExecutor", "SupervisorAgent", "ArchitectureAgents", "CodeAgent",
            "DeveloperAgent", "PatchRepairAgent", "TestAgents", "SecurityAgents", "IndependentReviewerAgent",
            "InvokeAgent");

    @Test
    void pipelineControlPlaneCannotInvokeAnInProcessAgent() {
        CONTROL_SOURCES.forEach(relative -> {
            String source = read(relative);
            DIRECT_AGENT_SYMBOLS.forEach(symbol -> assertThat(source)
                    .as("%s must not reference direct agent symbol %s", relative, symbol)
                    .doesNotContain(symbol));
        });
    }

    @Test
    void businessExecutionModeCannotSelectDirectOrA2aTransport() {
        for (String relative : CONTROL_SOURCES) {
            String source = read(relative);
            assertThat(source).as("%s must not select an agent transport", relative)
                    .doesNotContain("DIRECT", "transportMode", "agentTransport", "useA2a");
        }
    }

    @Test
    void pipelineStepsCannotInvokeTheLlmGatewayDirectly() {
        assertThat(read("service/PipelineStepService.java"))
                .doesNotContain("LlmGatewayClient");
    }

    @Test
    void productionWorkerRegistryBindsOnlyA2aAgentChildWorkflows() {
        String source = read("workflow/temporal/TemporalWorkerRegistry.java");
        assertThat(source).contains("A2aDelegationWorkflowImpl.class", "A2aIndependentReviewWorkflowImpl.class")
                .doesNotContain("\n                DelegationWorkflowImpl.class",
                        "\n                IndependentReviewWorkflowImpl.class");
    }

    private static String read(String relative) {
        Path path = Path.of("src/main/java/com/example/aifactory", relative);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Cannot inspect " + path, failure);
        }
    }
}
