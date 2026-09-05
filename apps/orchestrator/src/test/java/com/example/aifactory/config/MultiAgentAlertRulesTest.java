package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentAlertRulesTest {
    private static final Set<String> REQUIRED_ALERTS = Set.of(
            "AiFactoryAgentLoopDetected", "AiFactoryAgentBudgetExhausted", "AiFactoryAgentCostSpike",
            "AiFactoryTaskQueueBacklog", "AiFactorySandboxHeartbeatInvalid",
            "AiFactorySandboxExecutionFailures", "AiFactorySandboxMaintenanceFailure",
            "AiFactoryAgentContractError", "AiFactoryEvidenceAltered");

    @Test
    @SuppressWarnings("unchecked")
    void definesAndMountsNineActionableMultiAgentAlerts() throws Exception {
        Path root = repositoryRoot();
        Map<String, Object> document;
        try (var input = Files.newInputStream(
                root.resolve("infrastructure/observability/alerts/multiagents.yml"))) {
            document = new Yaml().load(input);
        }
        List<Map<String, Object>> groups = (List<Map<String, Object>>) document.get("groups");
        List<Map<String, Object>> rules = groups.stream()
                .flatMap(group -> ((List<Map<String, Object>>) group.get("rules")).stream()).toList();

        assertThat(rules.stream().map(rule -> rule.get("alert").toString()).collect(Collectors.toSet()))
                .isEqualTo(REQUIRED_ALERTS);
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.get("expr")).as("expression for %s", rule.get("alert")).isNotNull();
            assertThat(rule.get("for")).as("duration for %s", rule.get("alert")).isNotNull();
            assertThat((Map<String, Object>) rule.get("labels"))
                    .containsKeys("severity", "component");
            assertThat((Map<String, Object>) rule.get("annotations"))
                    .containsKeys("summary", "description", "runbook_url");
        });

        String prometheus = Files.readString(root.resolve("infrastructure/observability/prometheus.yml"));
        String compose = Files.readString(root.resolve("infrastructure/compose.yaml"));
        assertThat(prometheus).contains("/etc/prometheus/rules/*.yml");
        assertThat(compose).contains("./observability/alerts:/etc/prometheus/rules:ro");
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
