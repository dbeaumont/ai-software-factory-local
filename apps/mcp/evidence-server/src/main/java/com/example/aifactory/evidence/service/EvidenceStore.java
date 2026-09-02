package com.example.aifactory.evidence.service;

import com.example.aifactory.evidence.config.EvidenceProperties;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class EvidenceStore {
    private final Path root;
    private final int maxBytes;
    private final ObjectMapper mapper;
    private final EvidencePolicy policy;
    private final SecretKeySpec encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public EvidenceStore(EvidenceProperties properties, ObjectMapper mapper, EvidencePolicy policy) throws Exception {
        root = properties.stateRoot().normalize();
        maxBytes = properties.maxArtifactBytes();
        this.mapper = mapper;
        this.policy = policy;
        byte[] derived = MessageDigest.getInstance("SHA-256").digest(
                ("ai-factory:evidence:v1:" + properties.encryptionKey()).getBytes(StandardCharsets.UTF_8));
        encryptionKey = new SecretKeySpec(derived, "AES");
        Files.createDirectories(root);
        purgeExpired();
    }

    public synchronized StoredEvidence store(String taskId, String attemptId, String type, String mediaType,
                                              String contentBase64, String expectedDigest, String actor) throws Exception {
        validate(taskId, attemptId, type, mediaType, expectedDigest);
        EvidencePolicy.Rule rule = policy.requireWrite(type, actor);
        byte[] content;
        try { content = Base64.getDecoder().decode(contentBase64); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("evidence content is not valid base64"); }
        if (content.length > maxBytes) throw new IllegalArgumentException("evidence exceeds configured byte limit");
        String actual = digest(content);
        if (!constantDigest(expectedDigest, actual)) throw new SecurityException("evidence digest mismatch");
        Path attempt = attemptPath(taskId, attemptId);
        Files.createDirectories(attempt);
        Path target = attempt.resolve(type + '-' + actual + ".bin");
        byte[] associatedData = aad(taskId, attemptId, type, actual);
        try {
            Files.write(target, encrypt(content, associatedData), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException replay) {
            if (!MessageDigest.isEqual(decrypt(Files.readAllBytes(target), associatedData), content)) {
                throw new SecurityException("immutable evidence conflict");
            }
        }
        Instant storedAt = Files.getLastModifiedTime(target).toInstant();
        return new StoredEvidence("evidence://" + taskId + '/' + attemptId + '/' + type + '/' + actual,
                actual, "COMPLETE", mediaType, content.length, rule.classification(),
                storedAt.plus(Duration.ofDays(rule.retentionDays())), storedAt);
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
                || !patchDigest.equals(artifacts.get("patch").digest()) || policyDecision == null
                || !taskId.equals(policyDecision.taskId()) || !attemptId.equals(policyDecision.attemptId())) {
            throw new IllegalArgumentException("invalid evidence manifest request");
        }
        TreeMap<String, EvidenceReference> ordered = new TreeMap<>(artifacts);
        ordered.forEach((type, reference) -> verifyReference(taskId, attemptId, type, reference));
        Map<String, Object> payload = new TreeMap<>();
        payload.put("schema_version", "1"); payload.put("task_id", taskId); payload.put("attempt_id", attemptId);
        payload.put("repository_id", repositoryId); payload.put("source_commit", sourceCommit);
        payload.put("patch_digest", patchDigest); payload.put("artifacts", ordered);
        payload.put("policy_decision", policyMap(policyDecision));
        String manifestId = digest(mapper.writeValueAsBytes(payload));
        Instant createdAt = policyDecision.decidedAt();
        payload.put("manifest_id", manifestId); payload.put("created_at", createdAt.toString());
        byte[] document = mapper.writeValueAsBytes(payload);
        String documentDigest = digest(document);
        Path target = attemptPath(taskId, attemptId).resolve("manifest-" + manifestId + ".json");
        Files.createDirectories(target.getParent());
        byte[] associatedData = aad(taskId, attemptId, "manifest", manifestId);
        try { Files.write(target, encrypt(document, associatedData), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); }
        catch (java.nio.file.FileAlreadyExistsException replay) {
            if (!MessageDigest.isEqual(decrypt(Files.readAllBytes(target), associatedData), document)) {
                throw new SecurityException("immutable manifest conflict");
            }
        }
        EvidencePolicy.Rule rule = policy.require("manifest");
        return new StoredManifest(manifestId, "evidence://" + taskId + '/' + attemptId + "/manifest/" + manifestId,
                documentDigest, "COMPLETE", rule.classification(),
                createdAt.plus(Duration.ofDays(rule.retentionDays())), createdAt);
    }

    private void verifyReference(String taskId, String attemptId, String type, EvidenceReference reference) {
        if (reference == null || reference.digest() == null || !reference.digest().matches("[0-9a-f]{64}")
                || !("COMPLETE".equals(reference.status()) || "PARTIAL".equals(reference.status()))) {
            throw new IllegalArgumentException("invalid evidence reference: " + type);
        }
        String expectedUri = "evidence://" + taskId + '/' + attemptId + '/' + type + '/' + reference.digest();
        Path file = attemptPath(taskId, attemptId).resolve(type + '-' + reference.digest() + ".bin");
        if (!expectedUri.equals(reference.uri()) || !Files.isRegularFile(file)) {
            throw new SecurityException("manifest references unavailable or cross-task evidence: " + type);
        }
        try {
            String actual = digest(decrypt(Files.readAllBytes(file), aad(taskId, attemptId, type, reference.digest())));
            if (!constantDigest(actual, reference.digest())) throw new SecurityException("stored evidence digest mismatch: " + type);
        } catch (SecurityException exception) { throw exception; }
        catch (Exception exception) { throw new SecurityException("stored evidence cannot be verified: " + type); }
    }

    public ReadEvidence read(String taskId, String expectedAttemptId, String uri, String actor,
                             String purpose, boolean raw) throws Exception {
        java.net.URI parsed = java.net.URI.create(uri);
        String[] parts = parsed.getPath() == null ? new String[0] : parsed.getPath().split("/");
        if (!"evidence".equals(parsed.getScheme()) || !taskId.equals(parsed.getHost()) || parts.length != 4
                || parsed.getQuery() != null || parsed.getFragment() != null) {
            throw new SecurityException("evidence URI is outside task scope");
        }
        String attemptId = parts[1], type = parts[2], identifier = parts[3];
        if (!attemptId.equals(expectedAttemptId) || !attemptId.matches("[A-Za-z0-9_-]{1,128}")
                || !identifier.matches("[0-9a-f]{64}")) {
            throw new SecurityException("invalid evidence URI");
        }
        EvidencePolicy.Rule rule = raw ? policy.requireRead(type, actor, purpose) : policy.requireSummary(type, actor);
        Path file = attemptPath(taskId, attemptId).resolve(
                "manifest".equals(type) ? "manifest-" + identifier + ".json" : type + '-' + identifier + ".bin");
        if (!Files.isRegularFile(file)) throw new SecurityException("evidence is unavailable");
        byte[] clear = decrypt(Files.readAllBytes(file), aad(taskId, attemptId, type, identifier));
        if (!"manifest".equals(type) && !constantDigest(identifier, digest(clear))) {
            throw new SecurityException("evidence digest mismatch at read");
        }
        return new ReadEvidence(uri, type, identifier, "COMPLETE", rule.classification(), clear.length,
                raw ? Base64.getEncoder().encodeToString(clear) : null);
    }

    void purgeExpired() throws Exception {
        if (!Files.isDirectory(root)) return;
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (file.startsWith(root.resolve("audit"))) continue;
                String name = file.getFileName().toString();
                int separator = name.indexOf('-');
                if (separator < 1) continue;
                String type = name.startsWith("manifest-") ? "manifest" : name.substring(0, separator);
                EvidencePolicy.Rule rule = policy.require(type);
                if (Files.getLastModifiedTime(file).toInstant().plus(Duration.ofDays(rule.retentionDays())).isBefore(Instant.now())) Files.delete(file);
            }
        }
    }

    private Path attemptPath(String taskId, String attemptId) {
        Path attempt = root.resolve(taskId).resolve(attemptId).normalize();
        if (!attempt.startsWith(root)) throw new SecurityException("evidence path escapes storage root");
        return attempt;
    }
    private byte[] encrypt(byte[] clear, byte[] associatedData) throws Exception {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(128, nonce)); cipher.updateAAD(associatedData);
        byte[] ciphertext = cipher.doFinal(clear);
        return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
    }
    private byte[] decrypt(byte[] encrypted, byte[] associatedData) throws Exception {
        if (encrypted.length < 29) throw new SecurityException("encrypted evidence is truncated");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(128, Arrays.copyOfRange(encrypted, 0, 12)));
        cipher.updateAAD(associatedData);
        return cipher.doFinal(Arrays.copyOfRange(encrypted, 12, encrypted.length));
    }
    private static Map<String, Object> policyMap(PolicyDecision decision) {
        Map<String, Object> value = new TreeMap<>();
        value.put("schema_version", decision.schemaVersion()); value.put("task_id", decision.taskId());
        value.put("attempt_id", decision.attemptId()); value.put("policy_id", decision.policyId());
        value.put("policy_version", decision.policyVersion()); value.put("decision", decision.decision());
        value.put("reasons", decision.reasons()); value.put("input_digests", new TreeMap<>(decision.inputDigests()));
        value.put("decided_at", decision.decidedAt().toString()); return value;
    }
    private static byte[] aad(String task, String attempt, String type, String digest) { return String.join("\n", "1", task, attempt, type, digest).getBytes(StandardCharsets.UTF_8); }
    private static String digest(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static boolean constantDigest(String left, String right) { return MessageDigest.isEqual(HexFormat.of().parseHex(left), HexFormat.of().parseHex(right)); }
    private static void validate(String taskId, String attemptId, String type, String mediaType, String digest) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,64}") || attemptId == null
                || !attemptId.matches("[A-Za-z0-9_-]{1,128}") || type == null || !type.matches("[a-z][a-z0-9_-]{0,63}")
                || mediaType == null || !mediaType.matches("[a-z0-9.+-]+/[a-z0-9.+-]+")
                || digest == null || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("invalid evidence metadata");
    }

    public record StoredEvidence(String uri, String digest, String status, String mediaType, long sizeBytes,
                                 String classification, Instant retainUntil, Instant storedAt) {}
    public record EvidenceReference(String uri, String digest, String status) {}
    public record PolicyDecision(String schemaVersion, String taskId, String attemptId, String policyId,
                                 String policyVersion, String decision, List<String> reasons,
                                 Map<String, String> inputDigests, Instant decidedAt) {}
    public record StoredManifest(String manifestId, String uri, String digest, String status, String classification,
                                 Instant retainUntil, Instant createdAt) {}
    public record ReadEvidence(String uri, String type, String digest, String status, String classification,
                               long sizeBytes, String contentBase64) {}
}
