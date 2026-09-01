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
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {
    @Test
    void verifiesDigestAndKeepsIdempotentImmutableArtifact(@TempDir Path root) throws Exception {
        EvidenceStore store = new EvidenceStore(new EvidenceProperties(root, 1024), new ObjectMapper());
        byte[] content = "proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String encoded = Base64.getEncoder().encodeToString(content);
        EvidenceStore.StoredEvidence first = store.store("task-1", "attempt-1", "tests", "text/plain", encoded, digest);
        EvidenceStore.StoredEvidence replay = store.store("task-1", "attempt-1", "tests", "text/plain", encoded, digest);
        assertEquals(first.uri(), replay.uri());
        assertEquals(1, java.nio.file.Files.list(root.resolve("task-1/attempt-1")).count());
        assertThrows(SecurityException.class, () -> store.store("task-1", "attempt-1", "tests", "text/plain", encoded, "a".repeat(64)));
    }

    @Test
    void manifestAcceptsOnlyStoredSameAttemptArtifacts(@TempDir Path root) throws Exception {
        EvidenceStore store = new EvidenceStore(new EvidenceProperties(root, 1024), new ObjectMapper());
        Map<String, EvidenceStore.EvidenceReference> artifacts = new LinkedHashMap<>();
        for (String type : java.util.List.of("plan", "patch", "metadata", "tests", "sonar", "sbom", "trivy", "review", "approval")) {
            byte[] content = type.getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
            EvidenceStore.StoredEvidence stored = store.store("task-1", "attempt-1", type, "text/plain",
                    Base64.getEncoder().encodeToString(content), digest);
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
        String document = java.nio.file.Files.readString(root.resolve("task-1/attempt-1/manifest-" + first.manifestId() + ".json"));
        assertTrue(document.contains("\"schema_version\":\"1\""));
        assertTrue(document.contains("\"policy_decision\""));
        assertFalse(document.contains("schemaVersion"));
        Map<String, EvidenceStore.EvidenceReference> crossTask = new LinkedHashMap<>(artifacts);
        crossTask.put("tests", new EvidenceStore.EvidenceReference(
                artifacts.get("tests").uri().replace("task-1", "task-2"), artifacts.get("tests").digest(), "COMPLETE"));
        assertThrows(SecurityException.class, () -> store.createManifest("task-1", "attempt-1", "customer-api",
                "a".repeat(40), patchDigest, crossTask, decision));
    }
}
