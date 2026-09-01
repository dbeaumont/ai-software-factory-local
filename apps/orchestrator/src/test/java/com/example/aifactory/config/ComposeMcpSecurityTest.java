package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComposeMcpSecurityTest {
    @Test
    @SuppressWarnings("unchecked")
    void dockerSocketAndSandboxSecretsAreAbsentFromTheOrchestrator() throws Exception {
        Path compose = composeFile();
        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(compose)) {
            root = new Yaml().load(input);
        }
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) root.get("services");
        assertFalse(services.containsKey("ollama"));
        Map<String, Object> orchestrator = services.get("orchestrator");
        Map<String, Object> sandbox = services.get("sandbox-execution-mcp");
        Map<String, Object> litellm = services.get("litellm");

        List<String> orchestratorVolumes = (List<String>) orchestrator.get("volumes");
        assertTrue(orchestratorVolumes.stream().noneMatch(volume -> volume.contains("docker.sock")));
        Map<String, Object> orchestratorEnvironment = (Map<String, Object>) orchestrator.get("environment");
        assertFalse(orchestratorEnvironment.containsKey("ARTIFACTORY_TOKEN"));
        assertFalse(orchestratorEnvironment.containsKey("AI_FACTORY_SONAR_TOKEN"));
        assertFalse(orchestratorEnvironment.containsKey("AI_FACTORY_GITEA_TOKEN"));
        assertFalse(orchestratorEnvironment.containsKey("GITEA_TOKEN"));
        assertFalse(orchestratorEnvironment.containsKey("AI_FACTORY_LOCAL_MODEL"));
        assertFalse(((Map<String, Object>) litellm.get("environment")).containsKey("OLLAMA_MODEL"));

        long socketHolders = services.values().stream()
                .map(service -> (List<String>) service.getOrDefault("volumes", List.of()))
                .filter(volumes -> volumes.stream().anyMatch(volume -> volume.contains("docker.sock")))
                .count();
        assertEquals(1, socketHolders);
        assertTrue(((List<String>) sandbox.get("volumes")).stream()
                .anyMatch(volume -> volume.equals("/var/run/docker.sock:/var/run/docker.sock")));
        assertEquals(Boolean.TRUE, sandbox.get("read_only"));
        assertEquals(List.of("ALL"), sandbox.get("cap_drop"));
        assertTrue(((List<String>) sandbox.get("security_opt")).contains("no-new-privileges:true"));
    }

    private static Path composeFile() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : List.of(
                workingDirectory.resolve("infrastructure/compose.yaml"),
                workingDirectory.resolve("../../infrastructure/compose.yaml").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate infrastructure/compose.yaml from " + workingDirectory);
    }
}
