package com.example.aifactory.scm.service;

import com.example.aifactory.scm.config.ScmDeliveryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScmDeliveryServiceTest {
    @Test
    void validatesApprovalAndPatchBeforeSingleBusinessCommand(@TempDir Path root) throws Exception {
        Path workspaceRoot = root.resolve("workspace");
        Path workspace = workspaceRoot.resolve("task-1");
        Files.createDirectories(workspace);
        run(workspace, "git", "init", "-q");
        run(workspace, "git", "config", "user.email", "test@example.local");
        run(workspace, "git", "config", "user.name", "Test");
        Files.writeString(workspace.resolve("README.md"), "fixture\n");
        run(workspace, "git", "add", "README.md");
        run(workspace, "git", "commit", "-qm", "fixture");
        String sourceCommit = output(workspace, "git", "rev-parse", "HEAD").strip();
        byte[] patch = "diff --git a/a b/a\n".getBytes(StandardCharsets.UTF_8);
        Files.write(workspace.resolve("changes.patch"), patch);
        String patchDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(patch));
        Path registryFile = root.resolve("registry.json");
        Files.writeString(registryFile, """
                {"schema_version":"1","repositories":[{"repository_id":"customer-api","owner":"aiadmin",
                "name":"customer-api","clone_path":"/aiadmin/customer-api.git","allowed_base_branches":["main"]}]}
                """);
        Path tokenFile = root.resolve("token");
        Path approvalFile = root.resolve("approval");
        Files.writeString(tokenFile, "gitea-token-for-tests");
        Files.writeString(approvalFile, "approval-key-for-tests-at-least-32-bytes");
        Map<String, String> evidenceDigests = evidence(workspace);
        ScmDeliveryProperties properties = new ScmDeliveryProperties("http://gitea:3000", "http://localhost:3000",
                "delivery", tokenFile, root.resolve("state"), registryFile, workspaceRoot, approvalFile);
        ScmCredentials credentials = new ScmCredentials(properties);
        RepositoryRegistry registry = new RepositoryRegistry(properties, new ObjectMapper());
        AtomicReference<ScmDeliveryBackend.DeliveryCommand> captured = new AtomicReference<>();
        AtomicInteger creates = new AtomicInteger();
        ScmDeliveryBackend backend = new ScmDeliveryBackend() {
            @Override
            public DeliveryResult findExisting(DeliveryCommand command) {
                return null;
            }

            @Override
            public DeliveryResult createDraftPullRequest(DeliveryCommand command) {
                creates.incrementAndGet();
                captured.set(command);
                return new DeliveryResult("customer-api", command.branch(), "c".repeat(40), 42,
                        "http://localhost:3000/aiadmin/customer-api/pulls/42", true);
            }
        };
        ScmIdempotencyStore store = new ScmIdempotencyStore(properties, new ObjectMapper());
        ScmAuditLog audit = new ScmAuditLog(properties, new ObjectMapper());
        ScmWorktreeStager stager = new ScmWorktreeStager(properties);
        ScmDeliveryService service = new ScmDeliveryService(properties, credentials, registry, backend, stager, store, audit);
        Instant approvedAt = Instant.now().minusSeconds(5);
        ApprovalProof proof = ApprovalProof.sign("task-1", "attempt-1", "customer-api", sourceCommit,
                patchDigest, "David Beaumont", approvedAt, approvedAt.plusSeconds(3600), credentials.approvalKey());

        ScmDeliveryService.CreateRequest request = new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-1", "customer-api", sourceCommit, patchDigest, evidenceDigests, "main", "Add endpoint",
                "workflow", "delivery-task-1-attempt-1", proof);
        ScmDeliveryBackend.DeliveryResult result = service.create(request);
        ScmDeliveryBackend.DeliveryResult replay = service.create(request);

        assertEquals(42, result.pullRequestId());
        assertEquals("ai-factory/task-1-attempt-1", captured.get().branch());
        assertEquals(root.resolve("state/worktrees/task-1-attempt-1"), captured.get().workspace());
        assertEquals(result, replay);
        assertEquals(1, creates.get());
        ScmDeliveryService.CreateRequest unauthorized = new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-1", "customer-api", sourceCommit, patchDigest, evidenceDigests, "main", "Add endpoint",
                "delivery", "delivery-task-1-unauthorized", proof);
        assertThrows(IllegalArgumentException.class, () -> service.create(unauthorized));
        String auditEvents = Files.readString(root.resolve("state/audit/scm-delivery.jsonl"));
        assertEquals(2, auditEvents.lines().count());
        org.junit.jupiter.api.Assertions.assertTrue(auditEvents.contains("BEFORE_WRITE"));
        org.junit.jupiter.api.Assertions.assertTrue(auditEvents.contains("AFTER_WRITE"));
        org.junit.jupiter.api.Assertions.assertTrue(auditEvents.contains("delivery-task-1-attempt-1"));
        org.junit.jupiter.api.Assertions.assertTrue(auditEvents.contains("pull_request_id"));

        ScmDeliveryService.CreateRequest missingApproval = new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-missing", "customer-api", sourceCommit, patchDigest, evidenceDigests, "main", "title",
                "workflow", "delivery-task-1-missing", null);
        assertThrows(IllegalArgumentException.class, () -> service.create(missingApproval));

        ApprovalProof divergentProof = ApprovalProof.sign("task-1", "attempt-sha", "customer-api", "d".repeat(40),
                patchDigest, "David Beaumont", approvedAt, approvedAt.plusSeconds(3600), credentials.approvalKey());
        ScmDeliveryService.CreateRequest divergentSha = new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-sha", "customer-api", "d".repeat(40), patchDigest, evidenceDigests, "main", "title",
                "workflow", "delivery-task-1-sha", divergentProof);
        assertThrows(SecurityException.class, () -> service.create(divergentSha));

        ApprovalProof outsideProof = ApprovalProof.sign("task-1", "attempt-outside", "outside-repository",
                sourceCommit, patchDigest, "David Beaumont", approvedAt, approvedAt.plusSeconds(3600),
                credentials.approvalKey());
        ScmDeliveryService.CreateRequest outsideRepository = new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-outside", "outside-repository", sourceCommit, patchDigest, evidenceDigests, "main", "title",
                "workflow", "delivery-task-1-outside", outsideProof);
        assertThrows(IllegalArgumentException.class, () -> service.create(outsideRepository));

        ApprovalProof existingProof = ApprovalProof.sign("task-1", "attempt-existing", "customer-api", sourceCommit,
                patchDigest, "David Beaumont", approvedAt, approvedAt.plusSeconds(3600), credentials.approvalKey());
        ScmDeliveryService.CreateRequest existingRequest = new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-existing", "customer-api", sourceCommit, patchDigest, evidenceDigests, "main", "title",
                "workflow", "delivery-task-1-existing", existingProof);
        AtomicInteger existingCreates = new AtomicInteger();
        ScmDeliveryBackend existingBackend = new ScmDeliveryBackend() {
            @Override
            public DeliveryResult findExisting(DeliveryCommand command) {
                return new DeliveryResult("customer-api", command.branch(), "e".repeat(40), 43,
                        "http://localhost:3000/aiadmin/customer-api/pulls/43", true);
            }

            @Override
            public DeliveryResult createDraftPullRequest(DeliveryCommand command) {
                existingCreates.incrementAndGet();
                throw new AssertionError("existing branch/PR must be reused");
            }
        };
        ScmDeliveryService existingService = new ScmDeliveryService(properties, credentials, registry, existingBackend,
                stager, store, audit);
        assertEquals(43, existingService.create(existingRequest).pullRequestId());
        assertEquals(0, existingCreates.get());

        ApprovalProof timeoutProof = ApprovalProof.sign("task-1", "attempt-timeout", "customer-api", sourceCommit,
                patchDigest, "David Beaumont", approvedAt, approvedAt.plusSeconds(3600), credentials.approvalKey());
        ScmDeliveryService.CreateRequest timeoutRequest = new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-timeout", "customer-api", sourceCommit, patchDigest, evidenceDigests, "main", "title",
                "workflow", "delivery-task-1-timeout", timeoutProof);
        AtomicBoolean remotelyCreated = new AtomicBoolean();
        AtomicInteger timeoutCreates = new AtomicInteger();
        ScmDeliveryBackend timeoutBackend = new ScmDeliveryBackend() {
            @Override
            public DeliveryResult findExisting(DeliveryCommand command) {
                return remotelyCreated.get() ? new DeliveryResult("customer-api", command.branch(), "f".repeat(40),
                        44, "http://localhost:3000/aiadmin/customer-api/pulls/44", true) : null;
            }

            @Override
            public DeliveryResult createDraftPullRequest(DeliveryCommand command) {
                timeoutCreates.incrementAndGet();
                remotelyCreated.set(true);
                throw new IllegalStateException("simulated timeout after remote PR creation");
            }
        };
        ScmDeliveryService timeoutService = new ScmDeliveryService(properties, credentials, registry, timeoutBackend,
                stager, store, audit);
        assertThrows(IllegalStateException.class, () -> timeoutService.create(timeoutRequest));
        assertEquals(44, timeoutService.create(timeoutRequest).pullRequestId());
        assertEquals(1, timeoutCreates.get());
    }

    private static Map<String, String> evidence(Path workspace) throws Exception {
        Map<String, String> paths = Map.of(
                "plan", ".ai-plan.md", "tests", ".ai-factory/test.txt", "quality", ".ai-factory/sonar.txt",
                "sbom", ".ai-factory/sbom.cdx.json", "security", ".ai-factory/trivy.txt",
                "review", ".ai-review.md");
        Map<String, String> digests = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : paths.entrySet()) {
            Path file = workspace.resolve(entry.getValue());
            Files.createDirectories(file.getParent());
            byte[] content = (entry.getKey() + " evidence\n").getBytes(StandardCharsets.UTF_8);
            Files.write(file, content);
            digests.put(entry.getKey(), HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)));
        }
        return digests;
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }

    private static String output(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }
}
