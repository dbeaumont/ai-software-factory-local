package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeEffectIsolationTest {
    private static final List<String> FORBIDDEN_SOURCE_MARKERS = List.of(
            "jakarta.persistence", "org.springframework.data.jpa", "com.github.dockerjava",
            "org.eclipse.jgit", "DockerClient", "docker.sock",
            "com.example.aifactory.workflow.projection", "com.example.aifactory.scm");

    @Test
    void everyAgentRoleHasOnlyReadOnlyContextOrEvidenceTools() {
        new AgentCatalog().agentRoles().forEach(role -> {
            assertFalse(role.effectful(), role.name());
            assertTrue(role.tools().stream().allMatch(tool -> tool.startsWith("context.")
                    || tool.equals("evidence.get_summary") || tool.equals("evidence.read")), role.name());
        });
    }

    @Test
    void runtimeSourceCannotImportControlDataScmOrDockerClients() throws Exception {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    String source = Files.readString(path, StandardCharsets.UTF_8);
                    FORBIDDEN_SOURCE_MARKERS.forEach(marker ->
                            assertFalse(source.contains(marker), () -> path + " contains " + marker));
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }

    @Test
    void packagingAndRuntimeConfigurationExposeNoForbiddenBackend() throws Exception {
        String pom = Files.readString(Path.of("pom.xml"));
        for (String dependency : List.of("com.example:ai-factory-orchestrator",
                "org.springframework.boot:spring-boot-starter-data-jpa",
                "com.github.docker-java:*", "org.eclipse.jgit:*")) {
            assertTrue(pom.contains("<exclude>" + dependency + "</exclude>"), dependency);
        }
        String configuration = Files.readString(Path.of("src/main/resources/application.yml"));
        assertFalse(configuration.contains("scm-"));
        assertFalse(configuration.contains("sandbox-"));
        String dockerfile = Files.readString(Path.of("Dockerfile"));
        assertFalse(dockerfile.contains("/var/run/docker.sock"));
        assertFalse(dockerfile.contains("COPY --from=build /src/apps/orchestrator"));
        assertTrue(dockerfile.contains("USER 10001"));
    }
}
