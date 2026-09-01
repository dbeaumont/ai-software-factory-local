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

@Service
public class EvidenceStore {
    private final Path root;
    private final int maxBytes;

    public EvidenceStore(EvidenceProperties properties) throws Exception {
        root = properties.stateRoot().normalize();
        maxBytes = properties.maxArtifactBytes();
        Files.createDirectories(root);
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
}
