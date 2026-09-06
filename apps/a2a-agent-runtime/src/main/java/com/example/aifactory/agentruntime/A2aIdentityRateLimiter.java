package com.example.aifactory.agentruntime;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Bounded fixed-window limiter keyed by authenticated client identity and operation class. */
@Component
public final class A2aIdentityRateLimiter {
    private final A2aRateLimitProperties limits;
    private final Clock clock;
    private final Map<Key, Bucket> buckets = new HashMap<>();

    public A2aIdentityRateLimiter(A2aRateLimitProperties limits) {
        this(limits, Clock.systemUTC());
    }

    A2aIdentityRateLimiter(A2aRateLimitProperties limits, Clock clock) {
        this.limits = limits;
        this.clock = clock;
    }

    public synchronized void acquire(String identity, Operation operation) {
        if (identity == null || identity.isBlank() || operation == null) reject();
        long window = Math.floorDiv(clock.millis(), limits.window().toMillis());
        Key key = new Key(identity, operation);
        Bucket current = buckets.get(key);
        if (current == null && buckets.size() >= limits.maxTrackedIdentities()) {
            buckets.entrySet().removeIf(entry -> entry.getValue().window() < window);
            if (buckets.size() >= limits.maxTrackedIdentities()) reject();
        }
        if (current == null || current.window() != window) current = new Bucket(window, 0);
        if (current.count() >= maximum(operation)) reject();
        buckets.put(key, new Bucket(window, current.count() + 1));
    }

    private int maximum(Operation operation) {
        return switch (operation) {
            case REQUEST -> limits.requestsPerIdentity();
            case POLL -> limits.pollsPerIdentity();
            case CANCELLATION -> limits.cancellationsPerIdentity();
            case NOTIFICATION -> limits.notificationsPerRole();
        };
    }

    private static void reject() {
        throw new A2aOperationalException(A2aOperationalException.Category.QUOTA,
                "A2A identity rate limit exceeded", null);
    }

    public enum Operation { REQUEST, POLL, CANCELLATION, NOTIFICATION }
    private record Key(String identity, Operation operation) {}
    private record Bucket(long window, int count) {}
}
