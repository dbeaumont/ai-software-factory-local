package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryParityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesEveryHistoricalAlertSemanticExceptTheOtelCounterSuffix() throws Exception {
        Path root = repositoryRoot();
        JsonNode baseline = mapper.readTree(Files.readString(root.resolve(
                "docs/evidence/observability/prometheus-grafana-baseline-2026-09-05.json")));
        JsonNode target = mapper.readTree(Files.readString(root.resolve(
                "infrastructure/observability/signoz/rules/ai-factory.json")));
        Map<String, JsonNode> targetByName = new HashMap<>();
        target.forEach(rule -> targetByName.put(rule.path("alert").asText(), rule));

        assertThat(targetByName).hasSize(15);
        for (JsonNode source : baseline.path("alertRules")) {
            JsonNode rule = targetByName.get(source.path("name").asText());
            assertThat(rule).isNotNull();
            assertThat(rule.path("condition").path("compositeQuery").path("queries").path(0)
                    .path("spec").path("query").asText())
                    .isEqualTo(source.path("query").asText().replace("_total", ""));
            long historicalDuration = source.path("durationSeconds").asLong();
            Duration targetDuration = parseDuration(rule.path("evaluation").path("spec").path("evalWindow").asText());
            if (historicalDuration == 0) {
                assertThat(targetDuration).isEqualTo(Duration.ofMinutes(1));
            } else {
                assertThat(targetDuration).isEqualTo(Duration.ofSeconds(historicalDuration));
            }
            assertThat(rule.path("labels").path("severity").asText())
                    .isEqualTo(source.path("labels").path("severity").asText());
            assertThat(rule.path("labels").path("component").asText())
                    .isEqualTo(source.path("labels").path("component").asText());
            assertThat(rule.path("annotations").path("summary").asText())
                    .isEqualTo(source.path("annotations").path("summary").asText());
            assertThat(rule.path("annotations").path("description").asText())
                    .isEqualTo(source.path("annotations").path("description").asText());
            assertThat(rule.path("annotations").path("runbook_url").asText())
                    .isEqualTo(source.path("annotations").path("runbook_url").asText());
        }
    }

    @Test
    void mapsAllSixHistoricalDashboardsAndAddsCollectorCoverage() throws Exception {
        Path root = repositoryRoot();
        JsonNode baseline = mapper.readTree(Files.readString(root.resolve(
                "docs/evidence/observability/prometheus-grafana-baseline-2026-09-05.json")));
        Path dashboards = root.resolve("infrastructure/observability/signoz/dashboards");

        for (JsonNode source : baseline.path("dashboards")) {
            String fileName = Path.of(source.path("file").asText()).getFileName().toString();
            Path target = dashboards.resolve(fileName);
            assertThat(target).exists();
            JsonNode dashboard = mapper.readTree(Files.readString(target));
            long targetQueryCount = 0;
            for (JsonNode panel : dashboard.path("spec").path("panels")) {
                targetQueryCount += panel.path("spec").path("queries").path(0).path("spec")
                        .path("plugin").path("spec").path("queries").size();
            }
            assertThat(targetQueryCount)
                    .isGreaterThanOrEqualTo(source.path("expressions").size());
        }
        assertThat(Files.list(dashboards).filter(Files::isRegularFile).count()).isEqualTo(7);
        assertThat(dashboards.resolve("collector.json")).exists();
    }

    private static Duration parseDuration(String value) {
        long amount = Long.parseLong(value.substring(0, value.length() - 1));
        return switch (value.charAt(value.length() - 1)) {
            case 's' -> Duration.ofSeconds(amount);
            case 'm' -> Duration.ofMinutes(amount);
            case 'h' -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException("Unsupported duration: " + value);
        };
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
