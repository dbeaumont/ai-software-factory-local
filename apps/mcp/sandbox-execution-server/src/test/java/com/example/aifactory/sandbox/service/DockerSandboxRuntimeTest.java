package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
                2, 32, 2, 100, Duration.ofDays(7), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "", "", "");
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
    void selectsMavenProfileAndMountsMavenCache(@TempDir Path workspace) throws Exception {
        DockerSandboxRuntime runtime = runtime();
        Files.writeString(workspace.resolve("pom.xml"), "<project/>");

        SandboxProfiles.Profile profile = SandboxProfiles.forOperation(Operation.RUN_TESTS, workspace);
        List<String> command = runtime.command(profile, "ai-factory-sbx-ghi", workspace, null);

        assertEquals("test-maven-v1", profile.id());
        assertTrue(command.containsAll(List.of("--network", "factory-network")));
        assertTrue(command.contains("factory-workspace:/factory-tasks"));
        assertTrue(command.contains("ai-factory-m2:/root/.m2"));
        runtime.shutdown();
    }

    @Test
    void selectsGradleProfileWithoutMavenCache(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("gradlew"), "#!/bin/sh\n");

        SandboxProfiles.Profile profile = SandboxProfiles.forOperation(Operation.RUN_TESTS, workspace);

        assertEquals("test-gradle-v1", profile.id());
        assertFalse(profile.mavenCache());
        assertTrue(profile.script().contains("--no-daemon test"));
    }

    @Test
    void selectsLockedNodeProfileWithoutMavenCache(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("package.json"), "{}");

        SandboxProfiles.Profile profile = SandboxProfiles.forOperation(Operation.RUN_TESTS, workspace);

        assertEquals("test-node-v1", profile.id());
        assertFalse(profile.mavenCache());
        assertTrue(profile.script().contains("npm ci --ignore-scripts"));
        assertTrue(profile.script().contains("package-lock.json"));
    }

    @Test
    void rejectsWorkspaceWithoutSupportedTestManifest(@TempDir Path workspace) {
        assertThrows(IllegalArgumentException.class,
                () -> SandboxProfiles.forOperation(Operation.RUN_TESTS, workspace));
    }

    @Test
    void keepsServerSideEnvironmentValuesOutOfTheShellCommand() {
        String payload = "$(touch injected-from-environment)";
        SandboxExecutionProperties properties = new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network", 2, 32, 2, 100,
                Duration.ofDays(7), Duration.ofSeconds(15), 65_536, 1_048_576,
                "https://mirror.invalid/" + payload, payload, "https://sonar.invalid", payload);
        DockerSandboxRuntime runtime = new DockerSandboxRuntime(properties);

        List<String> command = runtime.command(SandboxProfiles.forOperation(Operation.RUN_QUALITY),
                "ai-factory-sbx-abc", Path.of("/workspace/tasks/task-1"), Path.of("/tmp/opaque-env-file"));

        assertFalse(String.join(" ", command).contains(payload));
        assertTrue(command.containsAll(List.of("--env-file", "/tmp/opaque-env-file")));
        runtime.shutdown();
    }

    @Test
    void treatsMaliciousFileNamesAndPatchLinesAsData(@TempDir Path repository) throws Exception {
        String maliciousName = "$(touch injected-from-filename)";
        Path maliciousFile = repository.resolve(maliciousName);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        Files.writeString(maliciousFile, "before\n", StandardCharsets.UTF_8);
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "initial");
        Files.writeString(maliciousFile, "after\n$(touch injected-from-content)\n", StandardCharsets.UTF_8);
        Files.writeString(repository.resolve("changes.patch"), output(repository, "git", "diff", "--binary"),
                StandardCharsets.UTF_8);
        run(repository, "git", "checkout", "--", maliciousName);

        run(repository, "bash", "-lc", SandboxProfiles.forOperation(Operation.VALIDATE_PATCH).script());

        assertFalse(Files.exists(repository.resolve("injected-from-filename")));
        assertFalse(Files.exists(repository.resolve("injected-from-content")));
    }

    private static DockerSandboxRuntime runtime() {
        SandboxExecutionProperties properties = new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 32, 2, 100, Duration.ofDays(7), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "", "", "");
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
    void retainsOnlyTheBoundedTailAndReportsTruncation() throws Exception {
        DockerSandboxRuntime runtime = runtime();
        Process process = new ProcessBuilder("bash", "-lc", "printf 'x%.0s' {1..70000}").start();

        DockerSandboxRuntime.BoundedOutput output = runtime.boundedOutput(process);

        assertEquals(0, process.waitFor());
        assertTrue(output.truncated());
        assertEquals(65_536, output.content().length());
        assertEquals("x".repeat(65_536), output.content());
        runtime.shutdown();
    }

    @Test
    void rejectsUnsafeJobRetentionBounds() {
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 32, 2, 100, Duration.ofSeconds(59), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 32, 2, 100, Duration.ofDays(366), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 32, 2, 100, Duration.ofDays(7), Duration.ofMillis(999),
                65_536, 1_048_576, "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                2, 32, 2, 100, Duration.ofDays(7), Duration.ofMinutes(6),
                65_536, 1_048_576, "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                1, 1, 3, 10, Duration.ofDays(7), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                1, -1, 1, 10, Duration.ofDays(7), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> new SandboxExecutionProperties(
                Path.of("/workspace/tasks"), Path.of("/state"), "factory-workspace",
                "sandbox@sha256:fixed", "factory-network",
                1, 1, 1, 10, Duration.ofDays(7), Duration.ofSeconds(15),
                65_536, 1_048_576, "", "token\nINJECTED=value", "", ""));
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static String output(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
