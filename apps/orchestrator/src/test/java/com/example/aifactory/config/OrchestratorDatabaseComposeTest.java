package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestratorDatabaseComposeTest {
    @Test
    @SuppressWarnings("unchecked")
    void applicationProjectionUsesItsOwnPrivatePersistentDatabase() throws Exception {
        Map<String, Object> root;
        try (var input = Files.newInputStream(composeFile())) {
            root = new Yaml().load(input);
        }
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) root.get("services");
        Map<String, Object> database = services.get("orchestrator-db");
        Map<String, Object> temporal = services.get("temporal-db");
        Map<String, Object> environment = (Map<String, Object>) database.get("environment");

        assertThat(database.get("image").toString()).startsWith("postgres:16-alpine@sha256:");
        assertThat((List<String>) database.get("volumes"))
                .containsExactly("orchestrator-db-data:/var/lib/postgresql/data");
        assertThat(database.get("networks")).isEqualTo(List.of("workflow-internal"));
        assertThat(environment).containsKeys("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD");
        assertThat(environment).isNotEqualTo(temporal.get("environment"));
        assertThat((Map<String, Object>) root.get("volumes")).containsKeys(
                "orchestrator-db-data", "temporal-db-data");
        assertThat((Map<String, Object>) services.get("orchestrator").get("depends_on"))
                .containsKey("orchestrator-db");
    }

    private static Path composeFile() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return List.of(cwd.resolve("infrastructure/compose.yaml"),
                        cwd.resolve("../../infrastructure/compose.yaml").normalize()).stream()
                .filter(Files::isRegularFile).findFirst().orElseThrow();
    }
}
