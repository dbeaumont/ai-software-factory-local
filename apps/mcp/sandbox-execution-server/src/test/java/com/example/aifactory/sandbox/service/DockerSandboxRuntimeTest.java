package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DockerSandboxRuntimeTest {
    @Test
    void buildsReadOnlyNetworklessValidationProfileWithoutBuildCache() {
        SandboxExecutionProperties properties = new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 100, Duration.ofDays(7), 65_536, 1_048_576, "", "", "", "");
        DockerSandboxRuntime runtime = new DockerSandboxRuntime(properties);

        List<String> command = runtime.command(SandboxProfiles.forOperation(Operation.VALIDATE_PATCH),
                "ai-factory-sbx-abc", Path.of("/workspace/tasks/task-1"), null);

        assertTrue(command.containsAll(List.of("--network", "none", "--memory", "2g", "--cpus", "2",
                "--pids-limit", "512", "--cap-drop", "ALL", "no-new-privileges")));
        assertTrue(command.contains("factory-workspace:/factory-tasks:ro"));
        assertFalse(command.contains("ai-factory-m2:/root/.m2"));
        assertTrue(command.contains("sandbox@sha256:fixed"));
        assertEquals("git apply --check changes.patch", command.getLast());
        assertFalse(command.contains("--privileged"));
        runtime.shutdown();
    }

    @Test
    void buildsWritableNetworklessApplyProfileWithoutBuildCache() {
        DockerSandboxRuntime runtime = runtime();

        List<String> command = runtime.command(SandboxProfiles.forOperation(Operation.APPLY_PATCH),
                "ai-factory-sbx-def", Path.of("/workspace/tasks/task-1"), null);

        assertTrue(command.containsAll(List.of("--network", "none", "--memory", "2g", "--cpus", "2",
                "--pids-limit", "512", "--cap-drop", "ALL", "no-new-privileges")));
        assertTrue(command.contains("factory-workspace:/factory-tasks"));
        assertFalse(command.contains("factory-workspace:/factory-tasks:ro"));
        assertFalse(command.contains("ai-factory-m2:/root/.m2"));
        assertEquals("git apply --check changes.patch && git apply changes.patch && git diff --check && git diff --stat",
                command.getLast());
        runtime.shutdown();
    }

    @Test
    void mountsMavenCacheOnlyForBuildProfiles() {
        DockerSandboxRuntime runtime = runtime();

        List<String> command = runtime.command(SandboxProfiles.forOperation(Operation.RUN_TESTS),
                "ai-factory-sbx-ghi", Path.of("/workspace/tasks/task-1"), null);

        assertTrue(command.containsAll(List.of("--network", "factory-network")));
        assertTrue(command.contains("factory-workspace:/factory-tasks"));
        assertTrue(command.contains("ai-factory-m2:/root/.m2"));
        runtime.shutdown();
    }

    private static DockerSandboxRuntime runtime() {
        SandboxExecutionProperties properties = new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 100, Duration.ofDays(7), 65_536, 1_048_576, "", "", "", "");
        return new DockerSandboxRuntime(properties);
    }

    @Test
    void recognizesOnlyControllerManagedContainerNames() {
        assertTrue(DockerSandboxRuntime.isManagedContainerName("ai-factory-sbx-" + "a".repeat(32)));
        assertFalse(DockerSandboxRuntime.isManagedContainerName("ai-factory-sbx-runtime-test-deadbeef"));
        assertFalse(DockerSandboxRuntime.isManagedContainerName("ai-factory-sbx-" + "a".repeat(31)));
        assertFalse(DockerSandboxRuntime.isManagedContainerName("other-" + "a".repeat(32)));
    }

    @Test
    void rejectsUnsafeJobRetentionBounds() {
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 100, Duration.ofSeconds(59), 65_536, 1_048_576, "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 100, Duration.ofDays(366), 65_536, 1_048_576, "", "", "", ""));
    }
}
