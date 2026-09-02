package com.example.aifactory.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Technology-neutral port for immutable workflow evidence. */
public interface EvidenceRepository {
    StoredEvidence store(StoreRequest request);

    StoredManifest createManifest(ManifestRequest request);

    EvidenceSummary getSummary(String taskId, String uri, String actor);

    RawEvidence read(ReadRequest request);

    record StoreRequest(String taskId, String attemptId, String type, String mediaType,
                        byte[] content, String digest, String actor) {
        public StoreRequest {
            content = content == null ? null : content.clone();
        }

        @Override public byte[] content() {
            return content == null ? null : content.clone();
        }
    }

    record EvidenceReference(String uri, String digest, String status) {}

    record PolicyDecision(String schemaVersion, String taskId, String attemptId, String policyId,
                          String policyVersion, String decision, List<String> reasons,
                          Map<String, String> inputDigests, Instant decidedAt) {
        public PolicyDecision {
            reasons = List.copyOf(reasons);
            inputDigests = Map.copyOf(inputDigests);
        }
    }

    record ManifestRequest(String taskId, String attemptId, String repositoryId, String sourceCommit,
                           String patchDigest, Map<String, EvidenceReference> artifacts,
                           PolicyDecision policyDecision, String actor) {
        public ManifestRequest {
            artifacts = Map.copyOf(artifacts);
        }
    }

    record ReadRequest(String taskId, String uri, String actor, String purpose) {}

    record StoredEvidence(String uri, String digest, String status, String mediaType, long sizeBytes,
                          String classification, Instant retainUntil, Instant storedAt) {}

    record StoredManifest(String manifestId, String uri, String digest, String status,
                          String classification, Instant retainUntil, Instant createdAt) {}

    record EvidenceSummary(String uri, String type, String digest, String status,
                           String classification, long sizeBytes) {}

    record RawEvidence(String uri, String type, String digest, String status,
                       String classification, byte[] content) {
        public RawEvidence {
            content = content == null ? null : content.clone();
        }

        @Override public byte[] content() {
            return content == null ? null : content.clone();
        }
    }
}
