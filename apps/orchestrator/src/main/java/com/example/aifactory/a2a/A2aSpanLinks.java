package com.example.aifactory.a2a;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;

import java.util.Map;
import java.util.function.Supplier;

/** Creates explicit A2A boundary spans linked to, rather than parented by, their source execution. */
public final class A2aSpanLinks {
    private final io.opentelemetry.api.trace.Tracer tracer;

    public A2aSpanLinks(OpenTelemetry telemetry) {
        this.tracer = telemetry.getTracer("ai.factory.a2a", "1.0");
    }

    public static A2aSpanLinks global() { return new A2aSpanLinks(GlobalOpenTelemetry.get()); }
    public static A2aSpanLinks disabled() { return new A2aSpanLinks(OpenTelemetry.noop()); }

    public <T> T call(String name, String relation, String traceparent,
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

    public void attribute(String name, String value) {
        Span.current().setAttribute(name, value);
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
