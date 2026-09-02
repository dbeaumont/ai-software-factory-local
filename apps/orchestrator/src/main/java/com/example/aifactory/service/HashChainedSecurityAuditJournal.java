package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Process-local HMAC chain; entries are immutable and any alteration or reordering is detectable. */
@Component
public final class HashChainedSecurityAuditJournal implements SecurityAuditJournal {
    private static final String GENESIS = "0".repeat(64);
    private final byte[] key;
    private final List<Entry> entries = new ArrayList<>();

    public HashChainedSecurityAuditJournal() {
        this(randomKey());
    }

    HashChainedSecurityAuditJournal(byte[] key) {
        if (key == null || key.length < 32) throw new IllegalArgumentException("Audit HMAC key is too short");
        this.key = key.clone();
    }

    @Override
    public synchronized Entry append(EventType type, String taskId, String actor,
                                     String objectReference, String decision) {
        require(type, taskId, actor, objectReference, decision);
        long sequence = entries.size() + 1L;
        String previous = entries.isEmpty() ? GENESIS : entries.getLast().digest();
        Instant occurredAt = Instant.now();
        String digest = sign(sequence, type, taskId, actor, objectReference, decision, occurredAt, previous);
        Entry entry = new Entry(sequence, type, taskId, actor, objectReference, decision,
                occurredAt, previous, digest);
        entries.add(entry);
        return entry;
    }

    @Override
    public synchronized List<Entry> list() {
        return List.copyOf(entries);
    }

    @Override
    public synchronized boolean verifyIntegrity() {
        return verify(entries);
    }

    boolean verify(List<Entry> candidate) {
        String previous = GENESIS;
        for (int index = 0; index < candidate.size(); index++) {
            Entry entry = candidate.get(index);
            if (entry.sequence() != index + 1L || !previous.equals(entry.previousDigest())
                    || !entry.digest().equals(sign(entry.sequence(), entry.type(), entry.taskId(), entry.actor(),
                    entry.objectReference(), entry.decision(), entry.occurredAt(), entry.previousDigest()))) {
                return false;
            }
            previous = entry.digest();
        }
        return true;
    }

    private String sign(long sequence, EventType type, String taskId, String actor,
                        String objectReference, String decision, Instant occurredAt, String previous) {
        try {
            String canonical = String.join("\n", Long.toString(sequence), type.name(), taskId, actor,
                    objectReference, decision, occurredAt.toString(), previous);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Audit HMAC is unavailable", exception);
        }
    }

    private static void require(EventType type, String taskId, String actor,
                                String objectReference, String decision) {
        if (type == null || !safe(taskId, 128) || !safe(actor, 256)
                || !safe(objectReference, 512) || !safe(decision, 128)) {
            throw new IllegalArgumentException("Audit event metadata is invalid");
        }
    }

    private static boolean safe(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max
                && value.chars().noneMatch(character -> character == '\r' || character == '\n');
    }

    private static byte[] randomKey() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return value;
    }
}
