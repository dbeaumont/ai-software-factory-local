package com.example.aifactory.agentcore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/** Emits fixed-schema A2A security decisions while replacing all supplied identifiers with irreversible digests. */
public final class A2aDecisionJournal {
    private final Clock clock;
    private final Consumer<String> sink;

    public A2aDecisionJournal() {
        this(Clock.systemUTC(), line -> System.getLogger("ai.factory.a2a.audit")
                .log(System.Logger.Level.INFO, line));
    }

    public A2aDecisionJournal(Clock clock, Consumer<String> sink) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public Entry record(EventType type, Outcome outcome, String actor, String task, String object) {
        Entry entry = new Entry(clock.instant(), Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(outcome, "outcome"), digest(actor), digest(task), digest(object));
        sink.accept(entry.toLogLine());
        return entry;
    }

    private static String digest(String value) {
        String normalized = value == null || value.isBlank() ? "absent" : value;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    public enum EventType { AUTHENTICATION, REFUSAL, CARD_CHANGE, DELEGATION, CANCELLATION, COLLISION }
    public enum Outcome { ALLOWED, DENIED, ACCEPTED, REJECTED, CHANGED, INVALIDATED }

    public record Entry(Instant occurredAt, EventType type, Outcome outcome,
                        String actorHash, String taskHash, String objectHash) {
        public String toLogLine() {
            return "event=a2a_decision occurred_at=" + occurredAt + " type=" + type + " outcome=" + outcome
                    + " actor_hash=" + actorHash + " task_hash=" + taskHash + " object_hash=" + objectHash;
        }
    }
}
