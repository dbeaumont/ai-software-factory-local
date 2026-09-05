package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentAlertRulesTest {
    private static final Set<String> REQUIRED_ALERTS = Set.of(
            "AiFactoryAgentLoopDetected", "AiFactoryAgentBudgetExhausted", "AiFactoryAgentCostSpike",
            "AiFactoryTaskQueueBacklog", "AiFactorySandboxHeartbeatInvalid",
            "AiFactorySandboxExecutionFailures", "AiFactorySandboxMaintenanceFailure",
            "AiFactoryAgentContractError", "AiFactoryEvidenceAltered");

    @Test
    void definesNineActionableSigNozAlerts() throws Exception {
        Path root = repositoryRoot();
        JsonNode rules = new ObjectMapper().readTree(Files.readString(
                root.resolve("infrastructure/observability/signoz/rules/ai-factory.json")));

        Set<String> names = StreamSupport.stream(rules.spliterator(), false)
                .map(rule -> rule.path("alert").asText()).collect(Collectors.toSet());
        assertThat(names).containsAll(REQUIRED_ALERTS).hasSize(15);
        assertThat(names).contains("AiFactoryCollectorExportFailures", "AiFactoryCollectorQueueSaturation",
                "AiFactoryTelemetryIngestionAbsent", "AiFactoryCollectorRestart",
                "AiFactoryCollectorMemoryPressure", "AiFactoryCollectorReceiverRefused");
        StreamSupport.stream(rules.spliterator(), false).forEach(rule -> {
            assertThat(rule.path("schemaVersion").asText()).isEqualTo("v2alpha1");
            assertThat(rule.path("condition").path("compositeQuery").path("queries").path(0)
                    .path("spec").path("query").asText()).isNotBlank();
            assertThat(rule.path("evaluation").path("spec").path("evalWindow").asText()).isNotBlank();
            assertThat(rule.path("labels").hasNonNull("severity")).isTrue();
            assertThat(rule.path("labels").hasNonNull("component")).isTrue();
            assertThat(rule.path("annotations").hasNonNull("summary")).isTrue();
            assertThat(rule.path("annotations").hasNonNull("description")).isTrue();
            assertThat(rule.path("annotations").hasNonNull("runbook_url")).isTrue();
            assertThat(rule.toString()).contains("ai-factory-local");
        });

        String compose = Files.readString(root.resolve("infrastructure/compose.yaml"));
        String bootstrapImage = Files.readString(
                root.resolve("infrastructure/observability/signoz-bootstrap.Dockerfile"));
        assertThat(compose).contains("signoz-bootstrap");
        assertThat(bootstrapImage).contains(
                "COPY infrastructure/observability/signoz/rules infrastructure/observability/signoz/rules");
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
