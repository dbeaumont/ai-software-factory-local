package com.example.aifactory.evidence.service;

import com.example.aifactory.evidence.config.EvidenceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.nio.file.attribute.FileTime;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {
    @Test
    void acceptsEveryEvidenceTypeEmittedByTheTemporalPipeline() {
        EvidencePolicy policy = new EvidencePolicy();

        for (String type : java.util.List.of(
                "plan", "patch", "patch-candidate", "patch-validation-error", "code-patch",
                "metadata", "tests", "quality", "security", "sbom", "review",
                "a2a-input-plan", "a2a-input-generate-patch", "a2a-input-repair-patch",
                "a2a-input-assess-tests", "a2a-input-review")) {
            assertDoesNotThrow(() -> policy.requireWrite(type, "workflow"), type);
        }
        assertEquals("CONFIDENTIAL", policy.requireWrite("security", "workflow").classification());
        assertThrows(SecurityException.class, () -> policy.requireWrite("unregistered", "workflow"));
        assertDoesNotThrow(() -> policy.requireRead("patch-validation-error", "workflow", "repair-patch"));
        assertDoesNotThrow(() -> policy.requireRead("agent-result", "workflow", "pipeline-a2a-result"));
        assertDoesNotThrow(() -> policy.requireRead(
                "code-patch", "workflow", "apply-patch-integration:" + "a".repeat(64)));
        assertThrows(SecurityException.class, () -> policy.requireRead(
                "code-patch", "reviewer", "apply-patch-integration:" + "a".repeat(64)));
        assertThrows(SecurityException.class, () -> policy.requireRead(
                "code-patch", "workflow", "apply-patch-integration:not-a-digest"));
        assertDoesNotThrow(() -> policy.requireRead(
                "plan", "developer", "agent-execution-input"));
        assertThrows(SecurityException.class, () -> policy.requireRead(
                "plan", "developer", "human-review"));
        assertThrows(SecurityException.class, () -> policy.requireRead(
                "approval", "developer", "agent-execution-input"));
    }

    @Test
    void verifiesDigestAndKeepsIdempotentImmutableArtifact(@TempDir Path root) throws Exception {
        EvidenceStore store = new EvidenceStore(new EvidenceProperties(root, 1024), new ObjectMapper(), new EvidencePolicy());
        byte[] content = "proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String encoded = Base64.getEncoder().encodeToString(content);
        EvidenceStore.StoredEvidence first = store.store("task-1", "attempt-1", "tests", "text/plain", encoded, digest, "workflow");
        EvidenceStore.StoredEvidence replay = store.store("task-1", "attempt-1", "tests", "text/plain", encoded, digest, "workflow");
        assertEquals(first.uri(), replay.uri());
        assertEquals(1, java.nio.file.Files.list(root.resolve("task-1/attempt-1")).count());
        byte[] encrypted = java.nio.file.Files.readAllBytes(root.resolve("task-1/attempt-1/tests-" + digest + ".bin"));
        assertFalse(new String(encrypted, StandardCharsets.UTF_8).contains("proof"));
        assertEquals("INTERNAL", first.classification());
        assertThrows(SecurityException.class, () -> store.store("task-1", "attempt-1", "tests", "text/plain", encoded, "a".repeat(64), "workflow"));
        assertThrows(SecurityException.class, () -> store.store("task-1", "attempt-1", "tests", "text/plain", encoded, digest, "agent"));
    }

    @Test
    void manifestAcceptsOnlyStoredSameAttemptArtifacts(@TempDir Path root) throws Exception {
        EvidenceStore store = new EvidenceStore(new EvidenceProperties(root, 1024), new ObjectMapper(), new EvidencePolicy());
        Map<String, EvidenceStore.EvidenceReference> artifacts = new LinkedHashMap<>();
        for (String type : java.util.List.of("plan", "patch", "tests", "quality", "security", "sbom", "review")) {
            byte[] content = type.getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            EvidenceStore.StoredEvidence stored = store.store("task-1", "attempt-1", type, "text/plain",
                    Base64.getEncoder().encodeToString(content), digest, "workflow");
            artifacts.put(type, new EvidenceStore.EvidenceReference(stored.uri(), stored.digest(), stored.status()));
        }
        String patchDigest = artifacts.get("patch").digest();
        EvidenceStore.PolicyDecision decision = new EvidenceStore.PolicyDecision("1", "task-1", "attempt-1",
                "delivery.gate", "1.0.0", "ALLOW", java.util.List.of("passed"), Map.of("tests", artifacts.get("tests").digest()),
                Instant.parse("2026-09-02T00:00:00Z"));
        EvidenceStore.StoredManifest first = store.createManifest("task-1", "attempt-1", "customer-api",
                "a".repeat(40), patchDigest, artifacts, decision);
        EvidenceStore.StoredManifest replay = store.createManifest("task-1", "attempt-1", "customer-api",
                "a".repeat(40), patchDigest, artifacts, decision);
        assertEquals(first.manifestId(), replay.manifestId());
        byte[] encryptedManifest = java.nio.file.Files.readAllBytes(root.resolve("task-1/attempt-1/manifest-" + first.manifestId() + ".json"));
        assertFalse(new String(encryptedManifest, StandardCharsets.UTF_8).contains("policy_decision"));
        assertEquals("CONFIDENTIAL", first.classification());
        Map<String, EvidenceStore.EvidenceReference> incomplete = new LinkedHashMap<>(artifacts);
        incomplete.remove("review");
        assertThrows(IllegalArgumentException.class, () -> store.createManifest("task-1", "attempt-1", "customer-api",
                "a".repeat(40), patchDigest, incomplete, decision));
        Map<String, EvidenceStore.EvidenceReference> crossTask = new LinkedHashMap<>(artifacts);
        crossTask.put("tests", new EvidenceStore.EvidenceReference(
                artifacts.get("tests").uri().replace("task-1", "task-2"), artifacts.get("tests").digest(), "COMPLETE"));
        assertThrows(SecurityException.class, () -> store.createManifest("task-1", "attempt-1", "customer-api",
                "a".repeat(40), patchDigest, crossTask, decision));
        Path testsFile = root.resolve("task-1/attempt-1/tests-" + artifacts.get("tests").digest() + ".bin");
        byte[] encrypted = java.nio.file.Files.readAllBytes(testsFile);
        encrypted[encrypted.length - 1] ^= 1;
        java.nio.file.Files.write(testsFile, encrypted);
        assertThrows(SecurityException.class, () -> store.createManifest("task-1", "attempt-1", "customer-api",
                "a".repeat(40), patchDigest, artifacts, decision));
    }

    @Test
    void summariesHideContentAndRawReadsAreExplicitlyAudited(@TempDir Path root) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EvidenceProperties properties = new EvidenceProperties(root, 1024);
        EvidenceStore store = new EvidenceStore(properties, mapper, new EvidencePolicy());
        EvidenceTools tools = new EvidenceTools(store, new EvidenceReadAudit(properties, mapper));
        byte[] content = "review proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        EvidenceTools.StoredEvidence stored = tools.store("1", "task-1", "attempt-1", "review", "text/plain",
                Base64.getEncoder().encodeToString(content), digest, "workflow");

        EvidenceTools.EvidenceSummary summary = tools.getSummary("1", "task-1", "attempt-1", stored.uri(), "planner");
        assertEquals(content.length, summary.sizeBytes());
        assertEquals(content.length, tools.getSummary(
                "1", "task-1", "attempt-1", stored.uri(), "security-agent").sizeBytes());
        assertThrows(SecurityException.class, () -> tools.getSummary(
                "1", "task-1", "attempt-1", stored.uri(), "architecture-agent"));
        assertThrows(SecurityException.class, () -> tools.getSummary(
                "1", "task-1", "attempt-2", stored.uri(), "planner"));
        assertThrows(SecurityException.class, () -> tools.read(
                "1", "task-1", "attempt-1", stored.uri(), "planner", "human-review"));
        EvidenceTools.RawEvidence raw = tools.read(
                "1", "task-1", "attempt-1", stored.uri(), "independent-reviewer", "human-review");
        assertArrayEquals(content, Base64.getDecoder().decode(raw.contentBase64()));
        String audit = java.nio.file.Files.readString(root.resolve("audit/raw-reads.jsonl"));
        assertTrue(audit.contains("DENIED"));
        assertTrue(audit.contains("ALLOWED"));
        assertTrue(audit.contains("independent-reviewer"));
        assertTrue(audit.contains("human-review"));
        assertTrue(audit.contains(stored.uri()));
        assertFalse(audit.contains("review proof"));
    }

    @Test
    void retainsAndRecoversUnexpiredEvidenceAfterStoreRestart(@TempDir Path root) throws Exception {
        EvidenceProperties properties = new EvidenceProperties(root, 1024);
        byte[] content = "durable proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        EvidenceStore first = new EvidenceStore(properties, new ObjectMapper(), new EvidencePolicy());
        EvidenceStore.StoredEvidence stored = first.store("task-1", "attempt-1", "review", "text/plain",
                Base64.getEncoder().encodeToString(content), digest, "workflow");

        EvidenceStore restarted = new EvidenceStore(properties, new ObjectMapper(), new EvidencePolicy());
        EvidenceStore.ReadEvidence recovered = restarted.read(
                "task-1", "attempt-1", stored.uri(), "independent-reviewer", "human-review", true);

        assertArrayEquals(content, Base64.getDecoder().decode(recovered.contentBase64()));
        assertEquals(digest, recovered.digest());
        assertEquals("COMPLETE", recovered.status());
    }

    @Test
    void legalHoldPreservesExpiredEvidenceUntilAuditedRelease(@TempDir Path root) throws Exception {
        EvidenceStore store = new EvidenceStore(new EvidenceProperties(root, 1024),
                new ObjectMapper(), new EvidencePolicy());
        byte[] content = "legally retained proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        EvidenceStore.StoredEvidence stored = store.store("task-1", "attempt-1", "tests", "text/plain",
                Base64.getEncoder().encodeToString(content), digest, "workflow");
        Path artifact = root.resolve("task-1/attempt-1/tests-" + digest + ".bin");
        java.nio.file.Files.setLastModifiedTime(artifact,
                FileTime.from(Instant.now().minus(java.time.Duration.ofDays(91))));

        EvidenceStore.LegalHold hold = store.placeLegalHold("task-1", "attempt-1", "legal-officer",
                "regulatory investigation 2026-42", Instant.now().plus(java.time.Duration.ofDays(30)));
        store.purgeExpired();

        assertTrue(java.nio.file.Files.isRegularFile(artifact));
        assertEquals(64, hold.reasonDigest().length());
        assertFalse(java.nio.file.Files.readString(root.resolve("audit/legal-holds.jsonl"))
                .contains("regulatory investigation"));
        assertThrows(SecurityException.class, () -> store.releaseLegalHold(
                "task-1", "attempt-1", "developer", "not authorized"));

        store.releaseLegalHold("task-1", "attempt-1", "security-officer", "case closed");
        store.purgeExpired();

        assertFalse(java.nio.file.Files.exists(artifact));
        String audit = java.nio.file.Files.readString(root.resolve("audit/legal-holds.jsonl"));
        assertTrue(audit.contains("PLACED"));
        assertTrue(audit.contains("RELEASED"));
        assertTrue(audit.contains(stored.uri().substring(11, stored.uri().indexOf('/', 11))));
    }

    @Test
    void malformedLegalHoldFailsClosedDuringPurge(@TempDir Path root) throws Exception {
        EvidenceStore store = new EvidenceStore(new EvidenceProperties(root, 1024),
                new ObjectMapper(), new EvidencePolicy());
        byte[] content = "proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        store.store("task-1", "attempt-1", "tests", "text/plain",
                Base64.getEncoder().encodeToString(content), digest, "workflow");
        Path artifact = root.resolve("task-1/attempt-1/tests-" + digest + ".bin");
        java.nio.file.Files.setLastModifiedTime(artifact,
                FileTime.from(Instant.now().minus(java.time.Duration.ofDays(91))));
        Path hold = root.resolve("legal-holds/task-1/attempt-1.json");
        java.nio.file.Files.createDirectories(hold.getParent());
        java.nio.file.Files.writeString(hold, "not-json");

        store.purgeExpired();

        assertTrue(java.nio.file.Files.isRegularFile(artifact));
    }
}
