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
        assertThat(manager.verify(first).sourceCommit()).isEqualTo(commit);
        assertThat(manager.verify(second).sourceCommit()).isEqualTo(commit);
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

    @Test
    void rejectsAnAllocationWhoseHeadDriftsFromTheVerifiedSourceCommit() throws Exception {
        Path repository = repository();
        String pinnedCommit = runner.run(List.of("git", "rev-parse", "HEAD"),
                repository, Duration.ofSeconds(5)).strip();
        Files.writeString(repository.resolve("app.txt"), "second revision\n");
        runner.run(List.of("git", "commit", "-am", "second"), repository, Duration.ofSeconds(5));
        String otherCommit = runner.run(List.of("git", "rev-parse", "HEAD"),
                repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager manager = new CodeWorkspaceManager(runner);
        CodeWorkspaceManager.Allocation allocation = manager.create(
                repository, temporaryDirectory.resolve("isolated-code"),
                new CodeWorkspaceManager.Request("task-1", "attempt-1", "developer-a", pinnedCommit));
        runner.run(List.of("git", "checkout", "--detach", otherCommit),
                allocation.path(), Duration.ofSeconds(5));

        assertThatThrownBy(() -> manager.verify(allocation))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("source commit drifted");
    }

    @Test
    void refusesAnOverlappingParallelBatchBeforeCreatingAnyWorktree() throws Exception {
        Path repository = repository();
        String commit = runner.run(List.of("git", "rev-parse", "HEAD"), repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager manager = new CodeWorkspaceManager(runner, new CodeScopePolicy());
        Path isolationRoot = temporaryDirectory.resolve("isolated-code");
        CodeScopePolicy.Scope broad = scope(CodeScopePolicy.Kind.DIRECTORY, "src");
        CodeScopePolicy.Scope nested = scope(CodeScopePolicy.Kind.FILE, "src/App.java");

        assertThatThrownBy(() -> manager.createParallel(repository, isolationRoot, List.of(
                new CodeWorkspaceManager.ScopedRequest(new CodeWorkspaceManager.Request(
                        "task-1", "attempt-1", "developer-a", commit), broad),
                new CodeWorkspaceManager.ScopedRequest(new CodeWorkspaceManager.Request(
                        "task-1", "attempt-1", "developer-b", commit), nested))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not provably disjoint");
        assertThat(Files.exists(isolationRoot)).isFalse();
    }

    @Test
    void cleanupRemovesOnlyTheExactAllocatedWorktreesAndIsIdempotent() throws Exception {
        Path repository = repository();
        String commit = runner.run(List.of("git", "rev-parse", "HEAD"),
                repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager manager = new CodeWorkspaceManager(runner);
        Path isolationRoot = temporaryDirectory.resolve("isolated-code");
        CodeWorkspaceManager.Allocation allocation = manager.create(repository, isolationRoot,
                new CodeWorkspaceManager.Request("task-1", "attempt-1", "developer-a", commit));
        Files.writeString(allocation.path().resolve("temporary.txt"), "temporary\n");

        manager.cleanup(repository, isolationRoot, List.of(allocation));
        manager.cleanup(repository, isolationRoot, List.of(allocation));

        assertThat(allocation.path()).doesNotExist();
        assertThat(isolationRoot).doesNotExist();
        assertThat(runner.run(List.of("git", "worktree", "list", "--porcelain"),
                repository, Duration.ofSeconds(5))).doesNotContain(allocation.worktreeId());
    }

    @Test
    void cleanupRejectsAPathThatDoesNotMatchTheAllocationIdentity() throws Exception {
        Path repository = repository();
        String commit = runner.run(List.of("git", "rev-parse", "HEAD"),
                repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager manager = new CodeWorkspaceManager(runner);
        Path isolationRoot = temporaryDirectory.resolve("isolated-code");
        CodeWorkspaceManager.Allocation allocation = manager.create(repository, isolationRoot,
                new CodeWorkspaceManager.Request("task-1", "attempt-1", "developer-a", commit));
        CodeWorkspaceManager.Allocation forged = new CodeWorkspaceManager.Allocation(
                allocation.worktreeId(), allocation.taskId(), allocation.attemptId(), allocation.nodeId(),
                allocation.sourceCommit(), temporaryDirectory.resolve("another-path"));

        assertThatThrownBy(() -> manager.cleanup(repository, isolationRoot, List.of(forged)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("differs from its allocation");
        assertThat(allocation.path()).exists();
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

    private static CodeScopePolicy.Scope scope(CodeScopePolicy.Kind kind, String path) {
        return new CodeScopePolicy.Scope("customer-api", java.util.Set.of(
                new CodeScopePolicy.Rule(kind, CodeScopePolicy.Access.WRITE, path)));
    }
}
