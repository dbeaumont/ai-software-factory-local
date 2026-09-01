package com.example.aifactory.evidence.service;

import com.example.aifactory.evidence.config.EvidenceProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceStoreTest {
    @Test
    void verifiesDigestAndKeepsIdempotentImmutableArtifact(@TempDir Path root) throws Exception {
        EvidenceStore store = new EvidenceStore(new EvidenceProperties(root, 1024));
        byte[] content = "proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        String encoded = Base64.getEncoder().encodeToString(content);
        EvidenceStore.StoredEvidence first = store.store("task-1", "attempt-1", "tests", "text/plain", encoded, digest);
        EvidenceStore.StoredEvidence replay = store.store("task-1", "attempt-1", "tests", "text/plain", encoded, digest);
        assertEquals(first.uri(), replay.uri());
        assertEquals(1, java.nio.file.Files.list(root.resolve("task-1/attempt-1")).count());
        assertThrows(SecurityException.class, () -> store.store("task-1", "attempt-1", "tests", "text/plain", encoded, "a".repeat(64)));
    }
}
