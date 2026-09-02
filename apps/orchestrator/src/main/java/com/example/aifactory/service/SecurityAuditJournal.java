package com.example.aifactory.service;

import java.time.Instant;
import java.util.List;

/** Append-only security event journal; implementations must detect record reordering or alteration. */
public interface SecurityAuditJournal {
    Entry append(EventType type, String taskId, String actor, String objectReference, String decision);
    List<Entry> list();
    boolean verifyIntegrity();

    enum EventType { AUTHORIZATION, REFUSAL, APPROVAL, MODE_CHANGE }

    record Entry(long sequence, EventType type, String taskId, String actor, String objectReference,
                 String decision, Instant occurredAt, String previousDigest, String digest) {}
}
