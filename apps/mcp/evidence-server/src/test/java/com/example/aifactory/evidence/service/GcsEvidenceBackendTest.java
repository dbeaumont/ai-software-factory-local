package com.example.aifactory.evidence.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class GcsEvidenceBackendTest {
    @Test
    void preservesLogicalUriAndRequiresImmutableLockedStorage() {
        GcsEvidenceBackend backend = new GcsEvidenceBackend(new GcsEvidenceBackend.Configuration(
                "ai-factory-evidence-prod", "projects/p/locations/europe-west1/keyRings/r/cryptoKeys/evidence",
                true, 30));
        String uri = "evidence://task-1/attempt-1/trivy/" + "a".repeat(64);
        GcsEvidenceBackend.ObjectDescriptor descriptor = backend.descriptor(uri, "a".repeat(64), "CONFIDENTIAL",
                Instant.now().plusSeconds(366L * 86400L), "scanner-result");
        assertEquals(uri, descriptor.logicalUri());
        assertEquals("v1/task-1/attempt-1/trivy/" + "a".repeat(64), descriptor.objectName());
        assertTrue(descriptor.generationMatchZero());
        assertEquals("generation-match-0", descriptor.metadata().get("write-precondition"));
        assertThrows(IllegalArgumentException.class, () -> new GcsEvidenceBackend(
                new GcsEvidenceBackend.Configuration("bucket-prod", "projects/p/key", false, 30)));
    }
}
