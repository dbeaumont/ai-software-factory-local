package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aMessageIdempotencyTest {
    @Test
    void derivesAStableUuidFromExecutionRoleSkillSequenceAndInputDigest() {
        String digest = A2aMessageIdentity.payloadDigest("payload".getBytes(StandardCharsets.UTF_8));
        String first = A2aMessageIdentity.derive(context(), "developer", "developer.code-task-v1", 1, digest);
        String replay = A2aMessageIdentity.derive(context(), "developer", "developer.code-task-v1", 1, digest);
        String next = A2aMessageIdentity.derive(context(), "developer", "developer.code-task-v1", 2, digest);
        assertThat(first).isEqualTo(replay).isNotEqualTo(next).matches("[0-9a-f-]{36}");
    }

    @Test
    void returnsTheSameServerTaskForAnIdenticalMessageAndPayload() {
        A2aMessageIdempotencyRegistry registry = new A2aMessageIdempotencyRegistry();
        AtomicInteger creations = new AtomicInteger();
        String messageId = A2aMessageIdentity.derive(context(), "developer", "developer.code-task-v1", 1,
                "b".repeat(64));
        var first = registry.register(messageId, "c".repeat(64), () -> {
            creations.incrementAndGet();
            return new A2aMessageIdempotencyRegistry.Assignment("server-task-1", "server-context-1");
        });
        var replay = registry.register(messageId, "c".repeat(64), () -> { throw new AssertionError(); });
        assertThat(first.duplicate()).isFalse();
        assertThat(replay.duplicate()).isTrue();
        assertThat(replay.assignment()).isEqualTo(first.assignment());
        assertThat(creations).hasValue(1);
    }

    @Test
    void rejectsSameMessageIdWithDifferentCanonicalPayload() {
        A2aMessageIdempotencyRegistry registry = new A2aMessageIdempotencyRegistry();
        String messageId = A2aMessageIdentity.derive(context(), "developer", "developer.code-task-v1", 1,
                "b".repeat(64));
        registry.register(messageId, "c".repeat(64),
                () -> new A2aMessageIdempotencyRegistry.Assignment("task-1", "context-1"));
        assertThatThrownBy(() -> registry.register(messageId, "d".repeat(64),
                () -> new A2aMessageIdempotencyRegistry.Assignment("task-2", "context-2")))
                .isInstanceOf(A2aMessageIdempotencyRegistry.CollisionException.class);
    }

    private static A2aExecutionContext context() {
        return new A2aExecutionContext("1", "task-1", "attempt-1", "workflow-1", "run-1", "customer-api",
                "a".repeat(40), "delegation-1", null, "developer", List.of("b".repeat(64)));
    }
}
