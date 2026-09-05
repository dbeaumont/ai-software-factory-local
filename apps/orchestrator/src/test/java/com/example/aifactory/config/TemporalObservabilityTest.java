package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalObservabilityTest {
    @Test
    @SuppressWarnings("unchecked")
    void exposesPrivateMetricsReadinessDashboardAndResourceCeilings() throws Exception {
        Path root = repositoryRoot();
        Map<String, Object> compose;
        try (var input = Files.newInputStream(root.resolve("infrastructure/compose.yaml"))) {
            compose = new Yaml().load(input);
        }
        Map<String, Map<String, Object>> services =
                (Map<String, Map<String, Object>>) compose.get("services");
        Map<String, Object> temporal = services.get("temporal");
        assertThat(temporal).containsKeys("healthcheck", "deploy");
        assertThat((Map<String, Object>) temporal.get("environment"))
                .containsEntry("PROMETHEUS_ENDPOINT", "0.0.0.0:8000");
        assertThat(resourceLimits(temporal)).containsKeys("cpus", "memory");
        assertThat(resourceLimits(services.get("temporal-db"))).containsKeys("cpus", "memory");
        assertThat(resourceLimits(services.get("temporal-ui"))).containsKeys("cpus", "memory");
        assertThat((List<String>) services.get("otel-collector").get("networks"))
                .contains("workflow-internal");

        String collector = Files.readString(root.resolve("infrastructure/observability/otel-collector.yaml"));
        assertThat(collector).contains("prometheus/temporal:", "targets: [temporal:8000]");
        String dashboard = Files.readString(
                root.resolve("infrastructure/observability/signoz/dashboards/temporal.json"));
        assertThat(dashboard).contains("AI Factory Temporal", "up{job=\\\"temporal\\\"}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resourceLimits(Map<String, Object> service) {
        Map<String, Object> deploy = (Map<String, Object>) service.get("deploy");
        Map<String, Object> resources = (Map<String, Object>) deploy.get("resources");
        return (Map<String, Object>) resources.get("limits");
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
