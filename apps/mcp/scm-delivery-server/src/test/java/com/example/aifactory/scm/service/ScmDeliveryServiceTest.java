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

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScmDeliveryServiceTest {
    @Test
    void validatesApprovalAndPatchBeforeSingleBusinessCommand(@TempDir Path root) throws Exception {
        Path workspaceRoot = root.resolve("workspace");
        Path workspace = workspaceRoot.resolve("task-1");
        Files.createDirectories(workspace);
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
        ScmDeliveryProperties properties = new ScmDeliveryProperties("http://gitea:3000", "http://localhost:3000",
                "delivery", tokenFile, root.resolve("state"), registryFile, workspaceRoot, approvalFile);
        ScmCredentials credentials = new ScmCredentials(properties);
        RepositoryRegistry registry = new RepositoryRegistry(properties, new ObjectMapper());
        AtomicReference<ScmDeliveryBackend.DeliveryCommand> captured = new AtomicReference<>();
        ScmDeliveryBackend backend = command -> {
            captured.set(command);
            return new ScmDeliveryBackend.DeliveryResult("customer-api", command.branch(), "c".repeat(40), 42,
                    "http://localhost:3000/aiadmin/customer-api/pulls/42", true);
        };
        ScmDeliveryService service = new ScmDeliveryService(properties, credentials, registry, backend);
        Instant approvedAt = Instant.now().minusSeconds(5);
        ApprovalProof proof = ApprovalProof.sign("task-1", "attempt-1", "customer-api", "a".repeat(40),
                patchDigest, "David Beaumont", approvedAt, approvedAt.plusSeconds(3600), credentials.approvalKey());

        ScmDeliveryBackend.DeliveryResult result = service.create(new ScmDeliveryService.CreateRequest("1", "task-1",
                "attempt-1", "customer-api", "a".repeat(40), patchDigest, "main", "Add endpoint", "delivery",
                "delivery-task-1-attempt-1", proof));

        assertEquals(42, result.pullRequestId());
        assertEquals("ai-factory/task-1-attempt-1", captured.get().branch());
        assertEquals(workspace, captured.get().workspace());
    }
}
