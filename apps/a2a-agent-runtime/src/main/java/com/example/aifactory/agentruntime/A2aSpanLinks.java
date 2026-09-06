package com.example.aifactory.agentruntime;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/** Replay-safe A2A span links: this component is invoked only by HTTP handlers and Temporal activities. */
@Component
final class A2aSpanLinks {
    private final io.opentelemetry.api.trace.Tracer tracer;

    A2aSpanLinks(OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer("ai.factory.a2a.agent", "1.0");
    }

    static A2aSpanLinks disabled() { return new A2aSpanLinks(OpenTelemetry.noop()); }

    <T> T call(String name, String relation, String traceparent,
               Map<String, String> attributes, Supplier<T> action) {
        SpanContext linked = traceparent == null ? Span.current().getSpanContext() : parse(traceparent);
        var builder = tracer.spanBuilder(name).setNoParent();
        if (linked.isValid()) {
            builder.addLink(linked, Attributes.of(AttributeKey.stringKey("a2a.link.relation"), relation));
        }
        attributes.forEach((key, value) -> builder.setAttribute(key, value));
        Span span = builder.startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return action.get();
        } catch (RuntimeException failure) {
            span.recordException(failure);
            span.setStatus(StatusCode.ERROR);
            throw failure;
        } finally {
            span.end();
        }
    }

    void run(String name, String relation, String traceparent,
             Map<String, String> attributes, Runnable action) {
        call(name, relation, traceparent, attributes, () -> { action.run(); return null; });
    }

    static SpanContext parse(String traceparent) {
        if (traceparent == null || !traceparent.matches(
                "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}")) {
            throw new IllegalArgumentException("A2A traceparent is invalid");
        }
        String[] fields = traceparent.split("-");
        return SpanContext.createFromRemoteParent(fields[1], fields[2],
                TraceFlags.fromHex(fields[3], 0), TraceState.getDefault());
    }
}
