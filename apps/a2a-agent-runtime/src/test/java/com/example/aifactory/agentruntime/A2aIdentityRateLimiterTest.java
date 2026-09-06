package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aIdentityRateLimiterTest {

    @Test
    void limitsEachIdentityAndOperationThenReopensTheNextWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-06T12:00:00Z"));
        A2aIdentityRateLimiter limiter = new A2aIdentityRateLimiter(new A2aRateLimitProperties(
                Duration.ofMinutes(1), 2, 1, 1, 1, 32), clock);

        limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.REQUEST);
        limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.REQUEST);
        assertQuota(() -> limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.REQUEST));

        limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.POLL);
        assertQuota(() -> limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.POLL));
        limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.CANCELLATION);
        assertQuota(() -> limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.CANCELLATION));
        limiter.acquire("developer", A2aIdentityRateLimiter.Operation.NOTIFICATION);
        assertQuota(() -> limiter.acquire("developer", A2aIdentityRateLimiter.Operation.NOTIFICATION));
        limiter.acquire("orchestrator-b", A2aIdentityRateLimiter.Operation.POLL);

        clock.advance(Duration.ofMinutes(1));
        limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.REQUEST);
        limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.POLL);
    }

    @Test
    void failsClosedWhenTheBoundedIdentityTableIsFull() {
        A2aIdentityRateLimiter limiter = new A2aIdentityRateLimiter(new A2aRateLimitProperties(
                Duration.ofMinutes(1), 1, 1, 1, 1, 1),
                Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneId.of("UTC")));

        limiter.acquire("orchestrator-a", A2aIdentityRateLimiter.Operation.REQUEST);
        assertQuota(() -> limiter.acquire("orchestrator-b", A2aIdentityRateLimiter.Operation.REQUEST));
    }

    private static void assertQuota(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(A2aOperationalException.class)
                .extracting(failure -> ((A2aOperationalException) failure).category())
                .isEqualTo(A2aOperationalException.Category.QUOTA);
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) { this.current = current; }

        private void advance(Duration duration) { current = current.plus(duration); }

        @Override
        public ZoneId getZone() { return ZoneId.of("UTC"); }

        @Override
        public Clock withZone(ZoneId zone) { return this; }

        @Override
        public Instant instant() { return current; }
    }
}
