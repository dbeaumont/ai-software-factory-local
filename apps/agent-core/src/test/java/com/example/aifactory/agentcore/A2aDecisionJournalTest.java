package com.example.aifactory.agentcore;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class A2aDecisionJournalTest {

    @Test
    void emitsOnlyFixedFieldsAndDigestsOfUntrustedValues() {
        AtomicReference<String> logged = new AtomicReference<>();
        A2aDecisionJournal journal = new A2aDecisionJournal(
                Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC), logged::set);

        journal.record(A2aDecisionJournal.EventType.AUTHENTICATION, A2aDecisionJournal.Outcome.DENIED,
                "client-secret\nforged=value", "task-sensitive", "Bearer token-sensitive");

        assertTrue(logged.get().matches("event=a2a_decision occurred_at=\\S+ type=AUTHENTICATION outcome=DENIED"
                + " actor_hash=[a-f0-9]{64} task_hash=[a-f0-9]{64} object_hash=[a-f0-9]{64}"));
        assertFalse(logged.get().contains("secret"));
        assertFalse(logged.get().contains("Bearer"));
        assertFalse(logged.get().contains("\n"));
    }
}
