package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class DockerSandboxRuntime implements SandboxRuntime {
    private static final Pattern MANAGED_CONTAINER = Pattern.compile("^ai-factory-sbx-[0-9a-f]{32}$");
    private final SandboxExecutionProperties properties;
    private final ExecutorService outputReaders = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, Process> runningClients = new ConcurrentHashMap<>();

    public DockerSandboxRuntime(SandboxExecutionProperties properties) {
        this.properties = properties;
    }

    @Override
    public RuntimeResult execute(Operation operation, String executionId, Path workspace) throws Exception {
        SandboxProfiles.Profile profile = SandboxProfiles.forOperation(operation);
        String containerName = "ai-factory-sbx-" + executionId;
        Path environmentFile = writeEnvironmentFile(executionId, profile.environmentNames());
        List<String> command = command(profile, containerName, workspace, environmentFile);
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            runningClients.put(executionId, process);
            Process started = process;
            Future<BoundedOutput> output = outputReaders.submit(() -> boundedOutput(started));
            boolean done = process.waitFor(profile.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroyForcibly();
                removeContainer(containerName);
                throw new RuntimeTimeoutException("sandbox profile timed out: " + profile.id());
            }
            BoundedOutput bounded = output.get(10, TimeUnit.SECONDS);
            return new RuntimeResult(process.exitValue(), bounded.content(), bounded.truncated());
        } catch (InterruptedException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            removeContainer(containerName);
            Thread.currentThread().interrupt();
            throw exception;
        } finally {
            runningClients.remove(executionId);
            if (environmentFile != null) {
                Files.deleteIfExists(environmentFile);
            }
        }
    }

    @Override
    public void cancel(String executionId) {
        Process process = runningClients.get(executionId);
        if (process != null) {
            process.destroyForcibly();
        }
        removeContainer("ai-factory-sbx-" + executionId);
    }

    @Override
    public void reconcileOrphans() throws Exception {
        Process process = new ProcessBuilder("docker", "ps", "-a", "--filter", "name=ai-factory-sbx-",
                "--format", "{{.Names}}").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("sandbox orphan discovery timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("sandbox orphan discovery failed");
        }
        for (String name : output.lines().map(String::strip).filter(value -> !value.isEmpty()).toList()) {
            if (isManagedContainerName(name)) {
                removeContainerChecked(name);
            }
        }
    }

    static boolean isManagedContainerName(String name) {
        return name != null && MANAGED_CONTAINER.matcher(name).matches();
    }

    List<String> command(SandboxProfiles.Profile profile, String containerName, Path workspace,
                         Path environmentFile) {
        String taskDirectory = workspace.getFileName().toString();
        String network = profile.network().equals("none") ? "none" : properties.network();
        String workspaceMount = properties.workspaceVolume() + ":/factory-tasks"
                + (profile.workspaceReadOnly() ? ":ro" : "");
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "--name", containerName,
                "--network", network,
                "--memory", "2g", "--cpus", "2", "--pids-limit", "512",
                "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
                "-v", workspaceMount,
                "-w", "/factory-tasks/" + taskDirectory));
        if (profile.mavenCache()) {
            command.addAll(List.of("-v", "ai-factory-m2:/root/.m2"));
        }
        if (environmentFile != null) {
            command.addAll(List.of("--env-file", environmentFile.toString()));
        }
        command.addAll(List.of(properties.image(), "bash", "-lc", profile.script()));
        return command;
    }

    private Path writeEnvironmentFile(String executionId, List<String> names) throws Exception {
        if (names.isEmpty()) {
            return null;
        }
        Map<String, String> allowed = new LinkedHashMap<>();
        allowed.put("MAVEN_MIRROR_URL", properties.mavenMirrorUrl());
        allowed.put("ARTIFACTORY_TOKEN", properties.artifactoryToken());
        allowed.put("SONAR_HOST_URL", properties.sonarqubeUrl());
        allowed.put("SONAR_TOKEN", properties.sonarToken());
        StringBuilder content = new StringBuilder();
        for (String name : names) {
            String value = allowed.getOrDefault(name, "");
            if (value != null && (value.contains("\n") || value.contains("\r"))) {
                throw new IllegalStateException("invalid newline in server-side sandbox configuration");
            }
            content.append(name).append('=').append(value == null ? "" : value).append('\n');
        }
        Path file = Path.of("/tmp", "sandbox-env-" + executionId);
        Files.writeString(file, content.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // POSIX permissions are enforced in the Linux runtime; tests may use another filesystem.
        }
        return file;
    }

    private BoundedOutput boundedOutput(Process process) throws Exception {
        StringBuilder retained = new StringBuilder();
        boolean truncated = false;
        try (InputStreamReader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                retained.append(buffer, 0, read);
                int overflow = retained.length() - properties.maxOutputChars();
                if (overflow > 0) {
                    retained.delete(0, overflow);
                    truncated = true;
                }
            }
        }
        return new BoundedOutput(retained.toString(), truncated);
    }

    private record BoundedOutput(String content, boolean truncated) {
    }

    private static void removeContainer(String containerName) {
        try {
            Process cleanup = new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true).start();
            cleanup.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            cleanup.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Best effort; job status remains timeout/cancelled and the controller logs no secrets.
        }
    }

    private static void removeContainerChecked(String containerName) throws Exception {
        Process cleanup = new ProcessBuilder("docker", "rm", "-f", containerName)
                .redirectErrorStream(true).start();
        cleanup.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        if (!cleanup.waitFor(10, TimeUnit.SECONDS)) {
            cleanup.destroyForcibly();
            throw new IllegalStateException("sandbox orphan cleanup timed out");
        }
        if (cleanup.exitValue() != 0) {
            throw new IllegalStateException("sandbox orphan cleanup failed");
        }
    }

    @PreDestroy
    void shutdown() {
        outputReaders.shutdownNow();
    }
}
