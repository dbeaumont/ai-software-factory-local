package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "AI_FACTORY_RUN_DOCKER_INTEGRATION_TESTS", matches = "true")
class DockerSandboxRuntimeIntegrationTest {
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    @Test
    void dockerDaemonAppliesTheEffectivePatchCheckConstraints() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String volume = "ai-factory-sandbox-runtime-test-" + suffix;
        String container = "ai-factory-sbx-runtime-test-" + suffix;
        String managedOrphan = "ai-factory-sbx-" + UUID.randomUUID().toString().replace("-", "");
        String configuredImage = System.getenv().getOrDefault("AI_FACTORY_SANDBOX_IMAGE", "ai-factory-sandbox:local");
        String image = configuredImage.matches("^(?:[a-zA-Z0-9][a-zA-Z0-9._:/-]*@)?sha256:[0-9a-f]{64}$")
                ? configuredImage
                : execute(List.of("docker", "image", "inspect", configuredImage, "--format", "{{.Id}}"));
        SandboxExecutionProperties properties = new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/tmp/sandbox-runtime-test-state"), volume, image,
                "unused-network",
                1, 4, 1, 10, Duration.ofDays(7), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "", "", "");
        DockerSandboxRuntime runtime = new DockerSandboxRuntime(properties);

        try {
            execute(List.of("docker", "volume", "create", volume));
            List<String> create = new ArrayList<>(runtime.command(
                    SandboxProfiles.forOperation(Operation.VALIDATE_PATCH), container,
                    Path.of("/workspace/tasks/runtime-test"), null));
            create.set(1, "create");
            create.remove("--rm");
            create.set(create.indexOf("-w") + 1, "/factory-tasks");
            create.set(create.size() - 1, "sleep 30");

            execute(create);
            execute(List.of("docker", "start", container));

            JsonNode inspection = new ObjectMapper()
                    .readTree(execute(List.of("docker", "inspect", container))).get(0);
            JsonNode hostConfig = inspection.path("HostConfig");

            assertTrue(inspection.path("State").path("Running").asBoolean());
            assertEquals("none", hostConfig.path("NetworkMode").asText());
            assertEquals(2L * 1024 * 1024 * 1024, hostConfig.path("Memory").asLong());
            assertEquals(2_000_000_000L, hostConfig.path("NanoCpus").asLong());
            assertEquals(512, hostConfig.path("PidsLimit").asInt());
            assertTrue(arrayContains(hostConfig.path("CapDrop"), "ALL"));
            assertTrue(arrayStartsWith(hostConfig.path("SecurityOpt"), "no-new-privileges"));

            JsonNode workspaceMount = findMount(inspection.path("Mounts"), "/factory-tasks");
            assertNotNull(workspaceMount, "the task workspace volume must be mounted");
            assertEquals(volume, workspaceMount.path("Name").asText());
            assertFalse(workspaceMount.path("RW").asBoolean(), "patch validation workspace must be read-only");
            assertNull(findMount(inspection.path("Mounts"), "/root/.m2"),
                    "patch validation must not receive the Maven cache");

            execute(List.of("docker", "create", "--name", managedOrphan, image, "sleep", "30"));
            execute(List.of("docker", "start", managedOrphan));
            assertTrue(containerExists(managedOrphan));

            runtime.reconcileOrphans();

            assertFalse(containerExists(managedOrphan), "managed orphan must be removed during reconciliation");
            assertTrue(containerExists(container), "non-managed test container must not be removed");
        } finally {
            executeIgnoringFailure(List.of("docker", "rm", "-f", managedOrphan));
            executeIgnoringFailure(List.of("docker", "rm", "-f", container));
            executeIgnoringFailure(List.of("docker", "volume", "rm", volume));
            runtime.shutdown();
        }
    }

    private static JsonNode findMount(JsonNode mounts, String destination) {
        for (JsonNode mount : mounts) {
            if (destination.equals(mount.path("Destination").asText())) {
                return mount;
            }
        }
        return null;
    }

    private static boolean arrayContains(JsonNode values, String expected) {
        for (JsonNode value : values) {
            if (expected.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean arrayStartsWith(JsonNode values, String prefix) {
        for (JsonNode value : values) {
            if (value.asText().startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String execute(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            fail("Docker command timed out: " + command.subList(0, Math.min(3, command.size())));
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static boolean containerExists(String containerName) throws Exception {
        Process process = new ProcessBuilder("docker", "inspect", containerName)
                .redirectErrorStream(true).start();
        process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            fail("Docker inspect timed out");
        }
        return process.exitValue() == 0;
    }

    private static void executeIgnoringFailure(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            // Best-effort cleanup of randomly named integration-test resources.
        }
    }
}
