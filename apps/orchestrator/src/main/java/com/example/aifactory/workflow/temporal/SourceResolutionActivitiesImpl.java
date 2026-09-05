package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.service.ProcessRunner;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/** Resolves a branch once into a workflow-owned workspace and returns a digest-bound attestation. */
@Component
public final class SourceResolutionActivitiesImpl implements SourceResolutionActivities {
    private final ProcessRunner runner;
    private final Path workspaceRoot;

    public SourceResolutionActivitiesImpl(ProcessRunner runner, AiFactoryProperties properties) {
        this.runner = runner;
        this.workspaceRoot = Path.of(properties.workspaceRoot()).toAbsolutePath().normalize();
    }

    @Override
    public Result resolve(Request request) {
        requireValid(request);
        Path workspace = workspaceRoot.resolve(request.taskId()).normalize();
        if (!workspace.startsWith(workspaceRoot) || Files.isSymbolicLink(workspace)) {
            throw new SecurityException("Source workspace escaped its configured root");
        }
        try {
            Files.createDirectories(workspaceRoot);
            if (!Files.exists(workspace)) {
                runner.run(List.of("git", "clone", "--depth", "1", "--branch", request.branch(),
                        request.repositoryUrl(), workspace.toString()), null, Duration.ofMinutes(2));
            } else {
                String remote = runner.run(List.of("git", "remote", "get-url", "origin"), workspace,
                        Duration.ofSeconds(10)).strip();
                if (!request.repositoryUrl().equals(remote)) {
                    throw new SecurityException("Existing source workspace belongs to another repository");
                }
            }
            String commit = runner.run(List.of("git", "rev-parse", "HEAD"), workspace,
                    Duration.ofSeconds(10)).strip();
            if (!commit.matches("[0-9a-f]{40}")) throw new SecurityException("Resolved source commit is invalid");
            String attestation = sha256(String.join("\u0000", request.repositoryId(), request.branch(), commit,
                    workspace.toString(), request.idempotencyKey()));
            return new Result(request.repositoryId(), request.branch(), commit, workspace.toString(), attestation);
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Source resolution failed", failure));
        }
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.repositoryId() == null || !request.repositoryId().matches("[a-z0-9][a-z0-9-]{1,62}")
                || request.branch() == null || !request.branch().matches("[A-Za-z0-9._/-]{1,128}")
                || request.branch().contains("..") || request.idempotencyKey() == null
                || !request.idempotencyKey().matches("effect-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Source resolution request is invalid");
        }
        URI uri;
        try {
            uri = URI.create(request.repositoryUrl());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Source repository URL is invalid", invalid);
        }
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPath() == null || !uri.getPath().matches("/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+\\.git")) {
            throw new IllegalArgumentException("Source repository URL is not an authorized clone URL");
        }
        String path = uri.getPath();
        String pathId = path.substring(path.lastIndexOf('/') + 1, path.length() - 4);
        if (!request.repositoryId().equals(pathId)) {
            throw new SecurityException("Source repository identity does not match its URL");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
