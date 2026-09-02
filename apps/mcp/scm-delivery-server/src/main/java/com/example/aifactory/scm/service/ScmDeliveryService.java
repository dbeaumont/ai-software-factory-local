package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ScmDeliveryService {
    private static final Map<String, String> EVIDENCE_PATHS = Map.of(
            "plan", ".ai-plan.md",
            "tests", ".ai-factory/test.txt",
            "quality", ".ai-factory/sonar.txt",
            "sbom", ".ai-factory/sbom.cdx.json",
            "security", ".ai-factory/trivy.txt",
            "review", ".ai-review.md");
    private final ScmDeliveryProperties properties;
    private final ScmCredentials credentials;
    private final RepositoryRegistry repositories;
    private final ScmDeliveryBackend backend;
    private final ScmWorktreeStager stager;
    private final ScmIdempotencyStore idempotency;
    private final ScmAuditLog audit;

    public ScmDeliveryService(ScmDeliveryProperties properties, ScmCredentials credentials,
                              RepositoryRegistry repositories, ScmDeliveryBackend backend, ScmWorktreeStager stager,
                              ScmIdempotencyStore idempotency, ScmAuditLog audit) {
        this.properties = properties;
        this.credentials = credentials;
        this.repositories = repositories;
        this.backend = backend;
        this.stager = stager;
        this.idempotency = idempotency;
        this.audit = audit;
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
        String workspaceCommit = gitHead(workspace);
        if (!request.sourceCommit().equals(workspaceCommit)) {
            throw new SecurityException("workspace source SHA does not match delivery request");
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(workspace.resolve("changes.patch"))));
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(digest), HexFormat.of().parseHex(request.patchDigest()))) {
            throw new SecurityException("patch digest does not match workspace evidence");
        }
        verifyEvidenceDigests(workspace, request.evidenceDigests());
        String branch = "ai-factory/" + request.taskId() + "-" + request.attemptId();
        String title = "AI Factory: " + abbreviate(request.title(), 90);
        audit.before(request, branch);
        Path stagedWorkspace = stager.stage(workspace, request.taskId(), request.attemptId());
        ScmDeliveryBackend.DeliveryResult result;
        try {
            ScmDeliveryBackend.DeliveryCommand command = new ScmDeliveryBackend.DeliveryCommand(repository, stagedWorkspace,
                    request.taskId(), request.sourceCommit(), request.baseBranch(), branch, title);
            result = backend.findExisting(command);
            if (result == null) {
                result = backend.createDraftPullRequest(command);
            }
        } finally {
            stager.delete(stagedWorkspace);
        }
        if (idempotency != null) {
            idempotency.save(request.idempotencyKey(), fingerprint, result);
        }
        audit.after(request, result);
        return result;
    }

    private static void validate(CreateRequest request) {
        if (!"1".equals(request.schemaVersion()) || !"workflow".equals(request.actor())
                || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.patchDigest() == null || !request.patchDigest().matches("[0-9a-f]{64}")
                || request.evidenceDigests() == null || !request.evidenceDigests().keySet().equals(EVIDENCE_PATHS.keySet())
                || request.evidenceDigests().values().stream().anyMatch(value -> value == null || !value.matches("[0-9a-f]{64}"))
                || request.idempotencyKey() == null || !request.idempotencyKey().matches("[A-Za-z0-9._:-]{8,128}")
                || request.approvalProof() == null) {
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
        String evidence = new TreeMap<>(request.evidenceDigests()).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
        String canonical = String.join("\n", request.taskId(), request.attemptId(), request.repositoryId(),
                request.sourceCommit(), request.patchDigest(), evidence, request.baseBranch(), request.approvalProof().signature());
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static void verifyEvidenceDigests(Path workspace, Map<String, String> supplied) throws Exception {
        for (Map.Entry<String, String> entry : EVIDENCE_PATHS.entrySet()) {
            Path evidence = workspace.resolve(entry.getValue()).normalize();
            if (!evidence.startsWith(workspace) || !Files.isRegularFile(evidence)) {
                throw new SecurityException("required delivery evidence is missing: " + entry.getKey());
            }
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(evidence));
            byte[] expected = HexFormat.of().parseHex(supplied.get(entry.getKey()));
            if (!MessageDigest.isEqual(actual, expected)) {
                throw new SecurityException("delivery evidence digest mismatch: " + entry.getKey());
            }
        }
    }

    private static String gitHead(Path workspace) throws Exception {
        Process process = new ProcessBuilder("git", "-c", "safe.directory=" + workspace,
                "rev-parse", "HEAD").directory(workspace.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
        if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0
                || !output.matches("[0-9a-f]{40}")) {
            throw new SecurityException("workspace has no verifiable source SHA");
        }
        return output;
    }

    public record CreateRequest(String schemaVersion, String taskId, String attemptId, String repositoryId,
                                String sourceCommit, String patchDigest, Map<String, String> evidenceDigests,
                                String baseBranch, String title, String actor,
                                String idempotencyKey, ApprovalProof approvalProof) {
        public CreateRequest {
            evidenceDigests = evidenceDigests == null ? Map.of() : Map.copyOf(evidenceDigests);
        }
    }
}
