package com.example.aifactory.a2a;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;

/** OTLP-ready client metrics with a closed, low-cardinality tag contract. */
@Component
public final class A2aClientMetrics {
    private final MeterRegistry registry;

    public A2aClientMetrics(MeterRegistry registry) { this.registry = registry; }

    public <T> T call(String role, String skill, String operation, Callable<T> action) {
        if (registry == null) return invoke(action);
        Timer.Sample sample = Timer.start(registry);
        try {
            T result = action.call();
            sample.stop(timer(role, skill, operation, "success"));
            return result;
        } catch (RuntimeException failure) {
            String result = timeout(failure) ? "timeout" : "error";
            sample.stop(timer(role, skill, operation, result));
            if ("timeout".equals(result)) increment("ai.factory.a2a.client.timeouts", role, skill, operation, result);
            throw failure;
        } catch (Exception failure) {
            sample.stop(timer(role, skill, operation, "error"));
            throw new IllegalStateException("A2A client operation failed", failure);
        }
    }

    public void payload(String role, String skill, int bytes) {
        if (registry == null) return;
        DistributionSummary.builder("ai.factory.a2a.client.payload.bytes")
                .baseUnit("bytes").tags(dimensions(role, skill, "send")).register(registry).record(bytes);
    }

    public void retry(String role, String skill) {
        increment("ai.factory.a2a.client.retries", role, skill, "reconcile", "retry");
    }

    public void reconciliation(String role, String result) {
        increment("ai.factory.a2a.client.reconciliations", role, "none", "reconcile", result);
    }

    public void divergence(String role) {
        increment("ai.factory.a2a.client.divergences", role, "none", "reconcile", "divergent");
    }

    public void cardValidation(String role, String result) {
        increment("ai.factory.a2a.client.card.validations", role, "none", "card", result);
    }

    public void notificationAge(String role, Instant occurredAt, Instant receivedAt) {
        if (registry == null || occurredAt == null || receivedAt == null) return;
        double milliseconds = Math.max(0, Duration.between(occurredAt, receivedAt).toMillis());
        DistributionSummary.builder("ai.factory.a2a.client.notification.age")
                .baseUnit("milliseconds").tags(dimensions(role, "none", "notify"))
                .register(registry).record(milliseconds);
    }

    public static A2aClientMetrics disabled() { return new A2aClientMetrics(null); }

    private Timer timer(String role, String skill, String operation, String result) {
        return Timer.builder("ai.factory.a2a.client.duration")
                .tags(dimensions(role, skill, operation).and("result", result)).register(registry);
    }

    private void increment(String name, String role, String skill, String operation, String result) {
        if (registry == null) return;
        Counter.builder(name).tags(dimensions(role, skill, operation).and("result", result))
                .register(registry).increment();
    }

    private static Tags dimensions(String role, String skill, String operation) {
        return new A2aMetricDimensions(role, skill, operation, "1.0", "none").tags();
    }

    private static boolean timeout(Throwable failure) {
        for (Throwable value = failure; value != null; value = value.getCause()) {
            if (value instanceof java.util.concurrent.TimeoutException
                    || value instanceof java.net.http.HttpTimeoutException) return true;
        }
        return false;
    }

    private static <T> T invoke(Callable<T> action) {
        try { return action.call(); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
