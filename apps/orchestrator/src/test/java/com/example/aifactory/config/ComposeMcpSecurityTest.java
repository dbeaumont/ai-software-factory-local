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
    void dockerSocketIsAbsentAndStaticRunnersAreHardened() throws Exception {
        Path compose = composeFile();
        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(compose)) {
            root = new Yaml().load(input);
        }
        Map<String, Map<String, Object>> services = (Map<String, Map<String, Object>>) root.get("services");
        assertFalse(services.containsKey("ollama"));
        Map<String, Object> orchestrator = services.get("orchestrator");
        Map<String, Object> sandbox = services.get("sandbox-execution-mcp");
        Map<String, Object> assurance = services.get("assurance-mcp");
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
        assertEquals(0, socketHolders);
        assertFalse(sandbox.containsKey("group_add"));
        assertEquals(Boolean.TRUE, sandbox.get("read_only"));
        assertEquals(List.of("ALL"), sandbox.get("cap_drop"));
        assertTrue(((List<String>) sandbox.get("security_opt")).contains("no-new-privileges:true"));
        assertFalse(assurance.containsKey("volumes"));
        assertFalse(assurance.containsKey("secrets"));
        assertEquals(List.of("mcp-internal"), assurance.get("networks"));
        assertEquals(Boolean.TRUE, assurance.get("read_only"));
        assertEquals(List.of("ALL"), assurance.get("cap_drop"));

        for (String name : List.of("sandbox-runner-readonly", "sandbox-runner-write",
                "sandbox-runner-dependency", "sandbox-runner-quality")) {
            Map<String, Object> runner = services.get(name);
            assertNotNull(runner, name + " must be declared");
            assertFalse(runner.containsKey("ports"), name + " must not publish host ports");
            assertFalse(runner.containsKey("privileged"), name + " must not be privileged");
            assertFalse(runner.containsKey("group_add"), name + " must not acquire supplementary host groups");
            assertEquals(Boolean.TRUE, runner.get("read_only"));
            assertEquals(List.of("ALL"), runner.get("cap_drop"));
            assertTrue(((List<String>) runner.get("security_opt")).contains("no-new-privileges:true"));
        }
        List<String> readOnlyVolumes = (List<String>) services.get("sandbox-runner-readonly").get("volumes");
        List<String> writeVolumes = (List<String>) services.get("sandbox-runner-write").get("volumes");
        assertTrue(readOnlyVolumes.stream().anyMatch(volume -> volume.endsWith(":/factory-tasks:ro")));
        assertTrue(writeVolumes.stream().anyMatch(volume -> volume.endsWith(":/factory-tasks")));
        assertTrue(writeVolumes.stream().noneMatch(volume -> volume.endsWith(":/factory-tasks:ro")));
        List<String> dependencyVolumes = (List<String>) services.get("sandbox-runner-dependency").get("volumes");
        assertTrue(dependencyVolumes.stream()
                .anyMatch(volume -> volume.endsWith(":/home/sandbox/.cache")),
                "security tools need a writable persistent cache outside the bounded tmpfs");
        assertEquals(List.of("sandbox-control"), services.get("sandbox-runner-readonly").get("networks"));
        assertEquals(List.of("sandbox-control"), services.get("sandbox-runner-write").get("networks"));
        assertEquals(List.of("sandbox-control", "sandbox-egress"),
                services.get("sandbox-runner-dependency").get("networks"));
        assertEquals(List.of("sandbox-control", "sandbox-quality"),
                services.get("sandbox-runner-quality").get("networks"));
        Map<String, Map<String, Object>> networks = (Map<String, Map<String, Object>>) root.get("networks");
        assertEquals(Boolean.TRUE, networks.get("sandbox-control").get("internal"));
        assertEquals(Boolean.TRUE, networks.get("sandbox-egress").get("internal"));
        assertEquals(Boolean.TRUE, networks.get("sandbox-quality").get("internal"));
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
