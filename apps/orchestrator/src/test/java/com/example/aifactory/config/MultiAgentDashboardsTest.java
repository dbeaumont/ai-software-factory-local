package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentDashboardsTest {
    private static final Map<String, String> REQUIRED = Map.of(
            "orchestrator.json", "AI Factory Global",
            "supervisor.json", "AI Factory Supervisor",
            "agents.json", "AI Factory Agents",
            "temporal.json", "AI Factory Temporal",
            "mcp.json", "AI Factory MCP",
            "sandbox.json", "AI Factory Sandbox");

    @Test
    void provisionsEveryOperationalPerimeterWithActionableQueries() throws Exception {
        Path dashboards = repositoryRoot().resolve("infrastructure/observability/grafana/dashboards");
        ObjectMapper mapper = new ObjectMapper();

        for (Map.Entry<String, String> expected : REQUIRED.entrySet()) {
            JsonNode dashboard = mapper.readTree(Files.readString(dashboards.resolve(expected.getKey())));
            assertThat(dashboard.path("title").asText()).isEqualTo(expected.getValue());
            assertThat(dashboard.path("uid").asText()).isNotBlank();
            assertThat(dashboard.path("panels").size()).isGreaterThanOrEqualTo(4);
            assertThat(dashboard.path("panels")).allSatisfy(panel -> {
                assertThat(panel.path("title").asText()).isNotBlank();
                assertThat(panel.path("targets").isArray()).isTrue();
                assertThat(panel.path("targets")).allSatisfy(target ->
                        assertThat(target.path("expr").asText()).isNotBlank());
            });
        }
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
