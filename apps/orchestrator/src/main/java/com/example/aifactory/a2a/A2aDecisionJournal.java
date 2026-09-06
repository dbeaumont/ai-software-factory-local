package com.example.aifactory.a2a;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Consumer;

/** Orchestrator-side emitter for the same fixed, digest-only A2A decision event schema as agent runtimes. */
public final class A2aDecisionJournal {
    private final Clock clock;
    private final Consumer<String> sink;

    public A2aDecisionJournal() {
        this(Clock.systemUTC(), line -> System.getLogger("ai.factory.a2a.audit")
                .log(System.Logger.Level.INFO, line));
    }

    A2aDecisionJournal(Clock clock, Consumer<String> sink) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    public void record(EventType type, Outcome outcome, String actor, String task, String object) {
        String line = "event=a2a_decision occurred_at=" + clock.instant() + " type="
                + Objects.requireNonNull(type) + " outcome=" + Objects.requireNonNull(outcome)
                + " actor_hash=" + digest(actor) + " task_hash=" + digest(task) + " object_hash=" + digest(object);
        sink.accept(line);
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
}
