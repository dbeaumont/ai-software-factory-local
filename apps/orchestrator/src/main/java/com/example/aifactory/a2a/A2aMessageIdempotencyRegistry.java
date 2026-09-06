package com.example.aifactory.a2a;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Atomic message deduplication contract used by the A2A server adapter. */
@Component
public final class A2aMessageIdempotencyRegistry {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public Resolution register(String messageId, String payloadDigest, Supplier<Assignment> createTask) {
        requireUuid(messageId);
        requireDigest(payloadDigest);
        AtomicBoolean created = new AtomicBoolean();
        Entry entry = entries.compute(messageId, (ignored, existing) -> {
            if (existing != null && !existing.payloadDigest().equals(payloadDigest)) {
                throw new CollisionException(messageId);
            }
            if (existing != null) return existing;
            Assignment assignment = createTask.get();
            if (assignment == null || assignment.taskId() == null || assignment.taskId().isBlank()
                    || assignment.contextId() == null || assignment.contextId().isBlank()) {
                throw new IllegalArgumentException("A2A server assignment is incomplete");
            }
            created.set(true);
            return new Entry(payloadDigest, assignment);
        });
        return new Resolution(entry.assignment(), !created.get());
    }

    private static void requireUuid(String value) {
        try { UUIDHolder.parse(value); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("messageId must be a UUID", exception); }
    }
    private static void requireDigest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid payload digest");
    }

    private record Entry(String payloadDigest, Assignment assignment) {}
    public record Assignment(String taskId, String contextId) {}
    public record Resolution(Assignment assignment, boolean duplicate) {}
    public static final class CollisionException extends SecurityException {
        CollisionException(String messageId) { super("A2A messageId collision: " + messageId); }
    }
    private static final class UUIDHolder {
        static void parse(String value) { java.util.UUID.fromString(value); }
    }
}
