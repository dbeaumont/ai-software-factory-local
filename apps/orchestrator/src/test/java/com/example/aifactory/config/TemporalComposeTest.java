package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalComposeTest {
    @Test
    @SuppressWarnings("unchecked")
    void temporalServerUsesDedicatedPersistentPostgresOnAPrivateNetwork() throws Exception {
        Map<String, Object> root;
        try (var input = Files.newInputStream(composeFile())) {
            root = new Yaml().load(input);
        }
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) root.get("services");
        Map<String, Object> database = services.get("temporal-db");
        Map<String, Object> schema = services.get("temporal-schema");
        Map<String, Object> temporal = services.get("temporal");
        Map<String, Object> namespace = services.get("temporal-namespace");

        assertThat(database.get("image").toString())
                .startsWith("postgres:16-alpine@sha256:")
                .matches("postgres:16-alpine@sha256:[0-9a-f]{64}");
        assertThat((List<String>) database.get("volumes"))
                .contains("temporal-db-data:/var/lib/postgresql/data");
        assertThat(database.get("networks")).isEqualTo(List.of("workflow-internal"));
        assertThat(schema.get("image").toString()).startsWith("temporalio/admin-tools:").doesNotEndWith("latest");
        assertThat(schema.get("image").toString()).startsWith("temporalio/admin-tools:1.31.2@");
        assertThat((Map<String, Object>) schema.get("depends_on")).containsKey("temporal-db");
        assertThat(temporal.get("image").toString()).startsWith("temporalio/server:").doesNotEndWith("latest");
        assertThat(temporal.get("image").toString()).startsWith("temporalio/server:1.31.2@");
        assertThat(temporal).doesNotContainKey("ports");
        assertThat(temporal.get("networks")).isEqualTo(List.of("workflow-internal"));
        assertThat((Map<String, Object>) namespace.get("environment"))
                .containsKeys("DEFAULT_NAMESPACE", "DEFAULT_NAMESPACE_RETENTION");
        assertThat((List<String>) services.get("orchestrator").get("networks"))
                .contains("workflow-internal");
        assertThat((Map<String, Object>) root.get("volumes")).containsKey("temporal-db-data");
        assertThat((Map<String, Object>) ((Map<String, Object>) root.get("networks")).get("workflow-internal"))
                .containsEntry("internal", true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void temporalUiIsBoundToLoopbackAndAbsentFromTheProductProxy() throws Exception {
        Map<String, Object> root;
        try (var input = Files.newInputStream(composeFile())) {
            root = new Yaml().load(input);
        }
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) root.get("services");
        Map<String, Object> ui = services.get("temporal-ui");

        assertThat(ui.get("image").toString()).startsWith("temporalio/ui:").doesNotEndWith("latest");
        assertThat((List<String>) ui.get("ports")).singleElement().asString().startsWith(
                "${TEMPORAL_UI_BIND_ADDRESS:-127.0.0.1}:");
        assertThat((List<String>) ui.get("networks"))
                .containsExactly("workflow-internal", "temporal-ui-host");
        assertThat((Map<String, Object>) ((Map<String, Object>) root.get("networks")).get("temporal-ui-host"))
                .doesNotContainKey("internal");
        assertThat(services.entrySet())
                .filteredOn(entry -> !entry.getKey().equals("temporal-ui"))
                .allSatisfy(entry -> {
                    Object networks = entry.getValue().get("networks");
                    if (networks instanceof List<?> networkNames) {
                        assertThat(networkNames.stream().map(Object::toString).toList())
                                .as(entry.getKey()).doesNotContain("temporal-ui-host");
                    }
                });
        assertThat(Files.readString(composeFile())).doesNotContain("proxy_pass http://temporal-ui");
    }

    private static Path composeFile() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        return List.of(cwd.resolve("infrastructure/compose.yaml"),
                        cwd.resolve("../../infrastructure/compose.yaml").normalize()).stream()
                .filter(Files::isRegularFile).findFirst().orElseThrow();
    }
}
