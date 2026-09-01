package com.example.aifactory.evidence.service;

import com.example.aifactory.evidence.config.EvidenceProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvidenceStore {
    private final Path root;
    private final int maxBytes;
    private final ObjectMapper mapper;

    public EvidenceStore(EvidenceProperties properties, ObjectMapper mapper) throws Exception {
        root = properties.stateRoot().normalize();
        maxBytes = properties.maxArtifactBytes();
        this.mapper = mapper;
        Files.createDirectories(root);
    }

    public synchronized StoredManifest createManifest(String taskId, String attemptId, String repositoryId,
                                                       String sourceCommit, String patchDigest,
                                                       Map<String, EvidenceReference> artifacts,
                                                       PolicyDecision policyDecision) throws Exception {
        Set<String> required = Set.of("plan", "patch", "metadata", "tests", "sonar", "sbom", "trivy", "review", "approval");
        if (repositoryId == null || !repositoryId.matches("[a-z0-9][a-z0-9-]{1,62}")
                || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                || patchDigest == null || !patchDigest.matches("[0-9a-f]{64}")
                || artifacts == null || !artifacts.keySet().equals(required)
                || !patchDigest.equals(artifacts.get("patch").digest())
                || policyDecision == null || !taskId.equals(policyDecision.taskId())
                || !attemptId.equals(policyDecision.attemptId())) {
            throw new IllegalArgumentException("invalid evidence manifest request");
        }
        TreeMap<String, EvidenceReference> ordered = new TreeMap<>(artifacts);
        ordered.forEach((type, reference) -> verifyReference(taskId, attemptId, type, reference));
        Map<String, Object> payload = new TreeMap<>();
        payload.put("schema_version", "1"); payload.put("task_id", taskId); payload.put("attempt_id", attemptId);
        payload.put("repository_id", repositoryId); payload.put("source_commit", sourceCommit);
        Map<String, Object> policy = new TreeMap<>();
        policy.put("schema_version", policyDecision.schemaVersion()); policy.put("task_id", policyDecision.taskId());
        policy.put("attempt_id", policyDecision.attemptId()); policy.put("policy_id", policyDecision.policyId());
        policy.put("policy_version", policyDecision.policyVersion()); policy.put("decision", policyDecision.decision());
        policy.put("reasons", policyDecision.reasons()); policy.put("input_digests", new TreeMap<>(policyDecision.inputDigests()));
        policy.put("decided_at", policyDecision.decidedAt().toString());
        payload.put("patch_digest", patchDigest); payload.put("artifacts", ordered); payload.put("policy_decision", policy);
        byte[] canonical = mapper.writeValueAsBytes(payload);
        String manifestId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        Instant createdAt = policyDecision.decidedAt();
        payload.put("manifest_id", manifestId); payload.put("created_at", createdAt.toString());
        byte[] document = mapper.writeValueAsBytes(payload);
        String documentDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document));
        Path attempt = root.resolve(taskId).resolve(attemptId).normalize();
        Files.createDirectories(attempt);
        Path target = attempt.resolve("manifest-" + manifestId + ".json");
        try { Files.write(target, document, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
        catch (java.nio.file.FileAlreadyExistsException replay) {
            if (!MessageDigest.isEqual(Files.readAllBytes(target), document)) {
                throw new SecurityException("immutable manifest conflict");
            }
        }
        return new StoredManifest(manifestId, "evidence://" + taskId + '/' + attemptId + "/manifest/" + manifestId,
                documentDigest, "COMPLETE", createdAt);
    }

    private void verifyReference(String taskId, String attemptId, String type, EvidenceReference reference) {
        if (reference == null || reference.digest() == null || !reference.digest().matches("[0-9a-f]{64}")
                || !("COMPLETE".equals(reference.status()) || "PARTIAL".equals(reference.status()))) {
            throw new IllegalArgumentException("invalid evidence reference: " + type);
        }
        String expectedUri = "evidence://" + taskId + '/' + attemptId + '/' + type + '/' + reference.digest();
        Path file = root.resolve(taskId).resolve(attemptId).resolve(type + '-' + reference.digest() + ".bin").normalize();
        if (!expectedUri.equals(reference.uri()) || !file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new SecurityException("manifest references unavailable or cross-task evidence: " + type);
        }
    }

    public synchronized StoredEvidence store(String taskId, String attemptId, String type, String mediaType,
                                              String contentBase64, String expectedDigest) throws Exception {
        validate(taskId, attemptId, type, mediaType, expectedDigest);
        byte[] content;
        try { content = Base64.getDecoder().decode(contentBase64); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("evidence content is not valid base64"); }
        if (content.length > maxBytes) throw new IllegalArgumentException("evidence exceeds configured byte limit");
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        if (!MessageDigest.isEqual(HexFormat.of().parseHex(expectedDigest), HexFormat.of().parseHex(actual))) {
            throw new SecurityException("evidence digest mismatch");
        }
        Path attempt = root.resolve(taskId).resolve(attemptId).normalize();
        if (!attempt.startsWith(root)) throw new SecurityException("evidence path escapes storage root");
        Files.createDirectories(attempt);
        Path target = attempt.resolve(type + '-' + actual + ".bin");
        try {
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException replay) {
            byte[] existing = Files.readAllBytes(target);
            if (!MessageDigest.isEqual(existing, content)) throw new SecurityException("immutable evidence conflict");
        }
        return new StoredEvidence("evidence://" + taskId + '/' + attemptId + '/' + type + '/' + actual,
                actual, "COMPLETE", mediaType, content.length, Instant.now());
    }

    private static void validate(String taskId, String attemptId, String type, String mediaType, String digest) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,64}") || attemptId == null
                || !attemptId.matches("[A-Za-z0-9_-]{1,128}") || type == null
                || !type.matches("[a-z][a-z0-9_-]{0,63}") || mediaType == null
                || !mediaType.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") || digest == null
                || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid evidence metadata");
    }

    public record StoredEvidence(String uri, String digest, String status, String mediaType, long sizeBytes,
                                 Instant storedAt) {}
    public record EvidenceReference(String uri, String digest, String status) {}
    public record PolicyDecision(String schemaVersion, String taskId, String attemptId, String policyId,
                                 String policyVersion, String decision, java.util.List<String> reasons,
                                 Map<String, String> inputDigests, Instant decidedAt) {}
    public record StoredManifest(String manifestId, String uri, String digest, String status, Instant createdAt) {}
}
