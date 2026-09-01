package com.example.aifactory.evidence.service;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Cloud Storage port prepared for MCP-151. The local POC keeps using EvidenceStore;
 * a production adapter can execute this immutable descriptor with Workload Identity.
 */
public final class GcsEvidenceBackend {
    private final Configuration configuration;

    public GcsEvidenceBackend(Configuration configuration) {
        if (configuration == null || configuration.bucket() == null
                || !configuration.bucket().matches("[a-z0-9][a-z0-9._-]{1,61}[a-z0-9]")
                || configuration.kmsKey() == null || !configuration.kmsKey().startsWith("projects/")
                || !configuration.retentionPolicyLocked() || configuration.minimumRetentionDays() < 1) {
            throw new IllegalArgumentException("GCS evidence backend requires a locked bucket and a CMEK key");
        }
        this.configuration = configuration;
    }

    public ObjectDescriptor descriptor(String logicalUri, String digest, String classification,
                                       Instant retainUntil, String attestationType) {
        URI uri = URI.create(logicalUri);
        String[] parts = uri.getPath() == null ? new String[0] : uri.getPath().split("/");
        if (!"evidence".equals(uri.getScheme()) || uri.getHost() == null || parts.length != 4
                || digest == null || !digest.matches("[0-9a-f]{64}") || retainUntil == null
                || retainUntil.isBefore(Instant.now().plusSeconds(configuration.minimumRetentionDays() * 86400L))
                || !("INTERNAL".equals(classification) || "CONFIDENTIAL".equals(classification))
                || attestationType == null || !attestationType.matches("[a-z][a-z0-9_.-]{1,63}")) {
            throw new IllegalArgumentException("invalid immutable GCS evidence descriptor");
        }
        String objectName = String.join("/", "v1", uri.getHost(), parts[1], parts[2], parts[3]);
        return new ObjectDescriptor(configuration.bucket(), objectName, logicalUri, digest,
                configuration.kmsKey(), retainUntil, true,
                Map.of("logical-uri", logicalUri, "sha256", digest, "classification", classification,
                        "attestation-type", attestationType, "write-precondition", "generation-match-0"));
    }

    public record Configuration(String bucket, String kmsKey, boolean retentionPolicyLocked,
                                int minimumRetentionDays) {}
    public record ObjectDescriptor(String bucket, String objectName, String logicalUri, String digest,
                                   String kmsKey, Instant retainUntil, boolean generationMatchZero,
                                   Map<String, String> metadata) {}
}
