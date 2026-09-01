package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiteaDeliveryBackendTest {
    @Test
    void commitExplicitlyExcludesFactoryArtifacts(@TempDir Path root) throws Exception {
        Path workspace = root.resolve("task-1");
        Files.createDirectories(workspace.resolve(".ai-factory"));
        run(workspace, "git", "init", "-q");
        run(workspace, "git", "config", "user.email", "test@example.local");
        run(workspace, "git", "config", "user.name", "Test");
        Files.writeString(workspace.resolve("app.txt"), "before\n");
        run(workspace, "git", "add", "app.txt");
        run(workspace, "git", "commit", "-qm", "initial");
        String sourceCommit = output(workspace, "git", "rev-parse", "HEAD").strip();
        Files.writeString(workspace.resolve("app.txt"), "after\n");
        Files.writeString(workspace.resolve(".ai-plan.md"), "plan");
        Files.writeString(workspace.resolve("changes.patch"), "patch");
        Files.writeString(workspace.resolve(".ai-review.md"), "review");
        Files.writeString(workspace.resolve(".ai-factory/evidence.txt"), "evidence");
        Path token = root.resolve("token");
        Path approval = root.resolve("approval");
        Files.writeString(token, "gitea-token-for-tests");
        Files.writeString(approval, "approval-key-for-tests-at-least-32-bytes");
        ScmDeliveryProperties properties = new ScmDeliveryProperties("http://gitea:3000", "http://localhost:3000",
                "delivery", token, root.resolve("state"), root.resolve("registry"), root, approval);
        GiteaDeliveryBackend backend = new GiteaDeliveryBackend(properties, new ScmCredentials(properties),
                WebClient.builder());
        RepositoryRegistry.RepositoryDefinition repository = new RepositoryRegistry.RepositoryDefinition(
                "customer-api", "aiadmin", "customer-api", "/aiadmin/customer-api.git", java.util.List.of("main"));

        backend.prepareCommit(new ScmDeliveryBackend.DeliveryCommand(repository, workspace, "task-1", sourceCommit,
                "main", "ai-factory/task-1-attempt-1", "AI Factory: test"));

        String names = output(workspace, "git", "show", "--pretty=format:", "--name-only", "HEAD");
        assertTrue(names.lines().anyMatch("app.txt"::equals));
        assertFalse(names.contains(".ai-plan.md"));
        assertFalse(names.contains("changes.patch"));
        assertFalse(names.contains(".ai-review.md"));
        assertFalse(names.contains(".ai-factory"));
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
    }

    private static String output(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IllegalStateException(output);
        return output;
    }
}
