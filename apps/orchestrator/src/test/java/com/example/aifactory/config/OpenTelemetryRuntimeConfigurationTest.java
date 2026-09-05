package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryRuntimeConfigurationTest {

    @Test
    @SuppressWarnings("unchecked")
    void routesThreeSignalsThroughOnePrivateHardenedCollector() throws Exception {
        Path root = repositoryRoot();
        Map<String, Object> collector;
        try (var input = Files.newInputStream(root.resolve("infrastructure/observability/otel-collector.yaml"))) {
            collector = new Yaml().load(input);
        }
        Map<String, Object> receivers = (Map<String, Object>) collector.get("receivers");
        Map<String, Object> processors = (Map<String, Object>) collector.get("processors");
        Map<String, Object> service = (Map<String, Object>) collector.get("service");
        Map<String, Object> pipelines = (Map<String, Object>) service.get("pipelines");

        assertThat(receivers).containsKeys("otlp", "prometheus/temporal");
        assertThat(processors).containsKeys("memory_limiter", "resource_detection", "transform/redact", "batch");
        assertThat(pipelines).containsKeys("traces", "metrics", "logs");
        assertThat(pipelines.values()).allSatisfy(pipeline ->
                assertThat((List<String>) ((Map<String, Object>) pipeline).get("exporters"))
                        .containsExactly("otlp_http/signoz"));

        String config = Files.readString(root.resolve("infrastructure/observability/otel-collector.yaml"));
        assertThat(config).contains("sending_queue:", "retry_on_failure:", "max_recv_msg_size_mib: 4");
        assertThat(config).contains("http.request.header.authorization", "gen_ai.prompt", "ai.evidence", "patch");

        String compose = Files.readString(root.resolve("infrastructure/compose.yaml"));
        assertThat(compose).contains("otel/opentelemetry-collector-contrib:0.160.0@sha256:");
        assertThat(compose).contains("read_only: true", "user: \"10001:10001\"", "cap_drop: [ALL]", "no-new-privileges:true");
        assertThat(compose).doesNotContain("4317:4317", "4318:4318");
    }

    @Test
    void removesLegacyRuntimeAndPrometheusRegistry() throws Exception {
        Path root = repositoryRoot();
        String compose = Files.readString(root.resolve("infrastructure/compose.yaml"));
        assertThat(compose).doesNotContain("\n  prometheus:", "\n  grafana:", "grafana-data");
        assertThat(Files.exists(root.resolve("infrastructure/observability/prometheus.yml"))).isFalse();
        assertThat(Files.walk(root.resolve("infrastructure/observability/grafana"))
                .anyMatch(Files::isRegularFile)).isFalse();
        assertThat(Files.walk(root.resolve("infrastructure/observability/alerts"))
                .anyMatch(Files::isRegularFile)).isFalse();

        for (Path pom : applicationFiles(root, "pom.xml")) {
            assertThat(Files.readString(pom)).doesNotContain("micrometer-registry-prometheus");
        }
        for (Path application : applicationFiles(root, "src/main/resources/application.yml")) {
            assertThat(Files.readString(application)).doesNotContain("/actuator/prometheus", "include: health,info,metrics,prometheus");
        }
    }

    @Test
    void configuresOtlpMetricsTracesAndLogsForEverySpringApplication() throws Exception {
        Path root = repositoryRoot();
        for (Path pom : applicationFiles(root, "pom.xml")) {
            assertThat(Files.readString(pom))
                    .contains("spring-boot-starter-opentelemetry")
                    .contains("opentelemetry-logback-appender-1.0");
        }
        for (Path application : applicationFiles(root, "src/main/resources/application.yml")) {
            assertThat(Files.readString(application))
                    .contains("management:", "opentelemetry:", "logging:", "otlp:")
                    .contains("service.namespace: ai-software-factory")
                    .contains("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                            "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT");
        }
        for (Path logback : applicationFiles(root, "src/main/resources/logback-spring.xml")) {
            assertThat(Files.readString(logback))
                    .contains("OpenTelemetryAppender", "CONSOLE", "OTEL")
                    .doesNotContain("captureCodeAttributes>true", "captureExperimentalAttributes>true");
        }
        assertThat(Files.readString(root.resolve("apps/orchestrator/src/main/resources/application.yml")))
                .contains("propagate-context: true", "type: W3C");
    }

    private static List<Path> applicationFiles(Path root, String relative) {
        return List.of(
                root.resolve("apps/orchestrator").resolve(relative),
                root.resolve("apps/mcp/repository-context-server").resolve(relative),
                root.resolve("apps/mcp/sandbox-execution-server").resolve(relative),
                root.resolve("apps/mcp/scm-delivery-server").resolve(relative),
                root.resolve("apps/mcp/assurance-server").resolve(relative),
                root.resolve("apps/mcp/evidence-server").resolve(relative));
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
