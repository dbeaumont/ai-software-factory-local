package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class ScmDeliveryService {
    private final ScmDeliveryProperties properties;
    private final ScmCredentials credentials;
    private final RepositoryRegistry repositories;
    private final ScmDeliveryBackend backend;
    private final ScmIdempotencyStore idempotency;

    public ScmDeliveryService(ScmDeliveryProperties properties, ScmCredentials credentials,
                              RepositoryRegistry repositories, ScmDeliveryBackend backend,
                              ScmIdempotencyStore idempotency) {
        this.properties = properties;
        this.credentials = credentials;
        this.repositories = repositories;
        this.backend = backend;
        this.idempotency = idempotency;
    }

    public ScmDeliveryBackend.DeliveryResult create(CreateRequest request) throws Exception {
        validate(request);
        String fingerprint = fingerprint(request);
        if (idempotency != null) {
            ScmDeliveryBackend.DeliveryResult replay = idempotency.find(request.idempotencyKey(), fingerprint);
            if (replay != null) {
                return replay;
            }
        }
        RepositoryRegistry.RepositoryDefinition repository = repositories.require(request.repositoryId());
        repository.requireBaseBranch(request.baseBranch());
        request.approvalProof().verify(credentials.approvalKey(), Instant.now());
        requireBoundApproval(request);
        Path workspace = properties.workspaceRoot().resolve(request.taskId()).normalize();
        if (!workspace.startsWith(properties.workspaceRoot()) || !Files.isDirectory(workspace)) {
            throw new SecurityException("task workspace is unavailable");
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(workspace.resolve("changes.patch"))));
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(digest), HexFormat.of().parseHex(request.patchDigest()))) {
            throw new SecurityException("patch digest does not match workspace evidence");
        }
        String branch = "ai-factory/" + request.taskId() + "-" + request.attemptId();
        String title = "AI Factory: " + abbreviate(request.title(), 90);
        ScmDeliveryBackend.DeliveryCommand command = new ScmDeliveryBackend.DeliveryCommand(repository, workspace,
                request.taskId(), request.sourceCommit(), request.baseBranch(), branch, title);
        ScmDeliveryBackend.DeliveryResult result = backend.findExisting(command);
        if (result == null) {
            result = backend.createDraftPullRequest(command);
        }
        if (idempotency != null) {
            idempotency.save(request.idempotencyKey(), fingerprint, result);
        }
        return result;
    }

    private static void validate(CreateRequest request) {
        if (!"1".equals(request.schemaVersion()) || !"delivery".equals(request.actor())
                || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.patchDigest() == null || !request.patchDigest().matches("[0-9a-f]{64}")
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("invalid SCM delivery request");
        }
    }

    private static void requireBoundApproval(CreateRequest request) {
        ApprovalProof proof = request.approvalProof();
        if (!request.taskId().equals(proof.taskId()) || !request.attemptId().equals(proof.attemptId())
                || !request.repositoryId().equals(proof.repositoryId())
                || !request.sourceCommit().equals(proof.sourceSha()) || !request.patchDigest().equals(proof.patchDigest())) {
            throw new SecurityException("approval proof is not bound to this delivery");
        }
    }

    private static String abbreviate(String value, int limit) {
        String safe = value == null || value.isBlank() ? "change" : value.replaceAll("[\\r\\n]+", " ").strip();
        return safe.length() <= limit ? safe : safe.substring(0, limit - 3) + "...";
    }

    private static String fingerprint(CreateRequest request) throws Exception {
        String canonical = String.join("\n", request.taskId(), request.attemptId(), request.repositoryId(),
                request.sourceCommit(), request.patchDigest(), request.baseBranch(), request.approvalProof().signature());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public record CreateRequest(String schemaVersion, String taskId, String attemptId, String repositoryId,
                                String sourceCommit, String patchDigest, String baseBranch, String title, String actor,
                                String idempotencyKey, ApprovalProof approvalProof) {
    }
}
