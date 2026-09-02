package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeWorkspaceManagerTest {
    @TempDir Path temporaryDirectory;
    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void createsOneDetachedAndWriteIsolatedWorktreePerCodeDelegation() throws Exception {
        Path repository = repository();
        String commit = runner.run(List.of("git", "rev-parse", "HEAD"), repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager manager = new CodeWorkspaceManager(runner);
        Path isolationRoot = temporaryDirectory.resolve("isolated-code");

        CodeWorkspaceManager.Allocation first = manager.create(repository, isolationRoot,
                new CodeWorkspaceManager.Request("task-1", "attempt-1", "developer-a", commit));
        CodeWorkspaceManager.Allocation second = manager.create(repository, isolationRoot,
                new CodeWorkspaceManager.Request("task-1", "attempt-1", "developer-b", commit));
        Files.writeString(first.path().resolve("app.txt"), "changed only in A\n");

        assertThat(first.worktreeId()).isNotEqualTo(second.worktreeId());
        assertThat(first.path()).isNotEqualTo(second.path()).startsWith(isolationRoot);
        assertThat(second.path()).startsWith(isolationRoot);
        assertThat(Files.readString(second.path().resolve("app.txt"))).isEqualTo("baseline\n");
        assertThat(Files.readString(repository.resolve("app.txt"))).isEqualTo("baseline\n");
        assertThat(runner.run(List.of("git", "rev-parse", "--abbrev-ref", "HEAD"),
                first.path(), Duration.ofSeconds(5)).strip()).isEqualTo("HEAD");
    }

    @Test
    void rejectsAWorktreeRootNestedInsideTheSourceAndDuplicateAllocation() throws Exception {
        Path repository = repository();
        String commit = runner.run(List.of("git", "rev-parse", "HEAD"), repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager manager = new CodeWorkspaceManager(runner);
        CodeWorkspaceManager.Request request = new CodeWorkspaceManager.Request(
                "task-1", "attempt-1", "developer-a", commit);

        assertThatThrownBy(() -> manager.create(repository, repository.resolve("nested"), request))
                .hasMessageContaining("outside the source");
        Path isolationRoot = temporaryDirectory.resolve("isolated-code");
        manager.create(repository, isolationRoot, request);
        assertThatThrownBy(() -> manager.create(repository, isolationRoot, request))
                .hasMessageContaining("already exists");
    }

    private Path repository() throws Exception {
        Path repository = temporaryDirectory.resolve("source");
        Files.createDirectories(repository);
        runner.run(List.of("git", "init"), repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "config", "user.email", "test@example.test"),
                repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "config", "user.name", "Test"), repository, Duration.ofSeconds(5));
        Files.writeString(repository.resolve("app.txt"), "baseline\n");
        runner.run(List.of("git", "add", "app.txt"), repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "commit", "-m", "baseline"), repository, Duration.ofSeconds(5));
        return repository;
    }
}
