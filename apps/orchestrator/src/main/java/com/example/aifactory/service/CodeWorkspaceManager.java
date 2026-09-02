package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Workflow-owned allocator of one detached Git worktree per Code delegation. */
@Component
public final class CodeWorkspaceManager {
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private final ProcessRunner runner;

    public CodeWorkspaceManager(ProcessRunner runner) {
        this.runner = runner;
    }

    public Allocation create(Path sourceRepository, Path isolationRoot, Request request) throws Exception {
        requireValid(request);
        Path source = sourceRepository.toRealPath();
        Path root = isolationRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        root = root.toRealPath();
        if (root.startsWith(source) || source.startsWith(root)) {
            throw new IllegalArgumentException("Code worktree isolation root must be outside the source worktree");
        }
        String worktreeId = worktreeId(request);
        Path destination = root.resolve(worktreeId).normalize();
        if (!destination.startsWith(root)) throw new SecurityException("Code worktree path escaped isolation root");
        runner.run(List.of("git", "cat-file", "-e", request.sourceCommit() + "^{commit}"),
                source, GIT_TIMEOUT);
        if (Files.exists(destination)) {
            throw new IllegalStateException("Code worktree already exists for delegation " + request.nodeId());
        }
        runner.run(List.of("git", "worktree", "add", "--detach", destination.toString(), request.sourceCommit()),
                source, GIT_TIMEOUT);
        return new Allocation(worktreeId, request.taskId(), request.attemptId(), request.nodeId(),
                request.sourceCommit(), destination);
    }

    static String worktreeId(Request request) {
        String identity = String.join("\u0000", request.taskId(), request.attemptId(), request.nodeId());
        try {
            String suffix = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
            return "worktree-" + request.nodeId() + '-' + suffix;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot derive Code worktree identity", exception);
        }
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.nodeId() == null || !request.nodeId().matches("[A-Za-z0-9_-]{1,64}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Code worktree request is invalid");
        }
    }

    public record Request(String taskId, String attemptId, String nodeId, String sourceCommit) {}

    public record Allocation(String worktreeId, String taskId, String attemptId, String nodeId,
                             String sourceCommit, Path path) {}
}
