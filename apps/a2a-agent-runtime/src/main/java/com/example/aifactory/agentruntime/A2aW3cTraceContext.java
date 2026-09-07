package com.example.aifactory.agentruntime;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/** Strict, bounded W3C context admitted from A2A metadata and scoped to one agent execution. */
record A2aW3cTraceContext(String traceparent, String baggage) {
    static final String EXTENSION = "https://ai-factory.local/extensions/w3c-trace-context/v1";
    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}");
    private static final Pattern BAGGAGE = Pattern.compile("[\\x20-\\x7e]{1,1024}");
    private static final ThreadLocal<A2aW3cTraceContext> CURRENT = new ThreadLocal<>();

    A2aW3cTraceContext {
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) {
            throw new IllegalArgumentException("A2A traceparent is invalid");
        }
        if (baggage != null && !BAGGAGE.matcher(baggage).matches()) {
            throw new IllegalArgumentException("A2A baggage is invalid or too large");
        }
    }

    static A2aW3cTraceContext from(JsonNode metadata) {
        JsonNode value = metadata == null ? null : metadata.get(EXTENSION);
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("Missing A2A W3C trace context");
        }
        JsonNode trace = value.get("traceparent");
        JsonNode baggage = value.get("baggage");
        return new A2aW3cTraceContext(trace == null ? null : trace.asText(),
                baggage == null || baggage.isNull() ? null : baggage.asText());
    }

    static A2aW3cTraceContext current() { return CURRENT.get(); }

    String traceId() { return traceparent.substring(3, 35); }

    static A2aW3cTraceContext propagatedFromCurrent(A2aW3cTraceContext fallback) {
        io.opentelemetry.api.trace.SpanContext active =
                io.opentelemetry.api.trace.Span.current().getSpanContext();
        if (!active.isValid()) return fallback;
        String flags = active.getTraceFlags().asHex();
        return new A2aW3cTraceContext(
                "00-" + active.getTraceId() + "-" + active.getSpanId() + "-" + flags, fallback.baggage());
    }

    <T> T call(Callable<T> action) {
        A2aW3cTraceContext previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return action.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("W3C-scoped agent execution failed", failure);
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    Map<String, Object> addTo(Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        result.put("traceparent", traceparent);
        if (baggage != null) result.put("baggage", baggage);
        return Map.copyOf(result);
    }
}
