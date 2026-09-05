package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentDashboardsTest {
    private static final Map<String, String> REQUIRED = Map.of(
            "orchestrator.json", "AI Factory Global",
            "supervisor.json", "AI Factory Supervisor",
            "agents.json", "AI Factory Agents",
            "temporal.json", "AI Factory Temporal",
            "mcp.json", "AI Factory MCP",
            "sandbox.json", "AI Factory Sandbox",
            "collector.json", "AI Factory OpenTelemetry Collector");

    @Test
    void provisionsEveryOperationalPerimeterWithActionableQueries() throws Exception {
        Path dashboards = repositoryRoot().resolve("infrastructure/observability/signoz/dashboards");
        ObjectMapper mapper = new ObjectMapper();

        for (Map.Entry<String, String> expected : REQUIRED.entrySet()) {
            JsonNode dashboard = mapper.readTree(Files.readString(dashboards.resolve(expected.getKey())));
            assertThat(dashboard.path("schemaVersion").asText()).isEqualTo("v6");
            assertThat(dashboard.path("spec").path("display").path("name").asText())
                    .isEqualTo(expected.getValue());
            JsonNode panels = dashboard.path("spec").path("panels");
            assertThat(panels.size()).isGreaterThanOrEqualTo(4);
            StreamSupport.stream(panels.spliterator(), false).forEach(panel -> {
                assertThat(panel.path("spec").path("display").path("name").asText()).isNotBlank();
                JsonNode queries = panel.path("spec").path("queries").path(0)
                        .path("spec").path("plugin").path("spec").path("queries");
                assertThat(queries.isArray()).isTrue();
                StreamSupport.stream(queries.spliterator(), false).forEach(query ->
                        assertThat(query.path("spec").path("query").asText()).isNotBlank());
            });
        }
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
