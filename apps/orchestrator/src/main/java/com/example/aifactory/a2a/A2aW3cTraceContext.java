package com.example.aifactory.a2a;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded W3C context transported in A2A metadata between the two Temporal control boundaries. */
public record A2aW3cTraceContext(String traceparent, String baggage) {
    public static final String EXTENSION = "https://ai-factory.local/extensions/w3c-trace-context/v1";
    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}");
    private static final Pattern BAGGAGE = Pattern.compile("[\\x20-\\x7e]{1,1024}");

    public A2aW3cTraceContext {
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()) {
            throw new IllegalArgumentException("A2A traceparent is invalid");
        }
        if (baggage != null && !BAGGAGE.matcher(baggage).matches()) {
            throw new IllegalArgumentException("A2A baggage is invalid or too large");
        }
    }

    public static A2aW3cTraceContext capture() {
        Map<String, String> carrier = new LinkedHashMap<>();
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .inject(Context.current(), carrier, Map::put);
        String traceparent = carrier.get("traceparent");
        if (traceparent == null) return null;
        return new A2aW3cTraceContext(traceparent, carrier.get("baggage"));
    }

    public Map<String, Object> addTo(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        Map<String, String> value = new LinkedHashMap<>();
        value.put("traceparent", traceparent);
        if (baggage != null) value.put("baggage", baggage);
        result.put(EXTENSION, Map.copyOf(value));
        return Map.copyOf(result);
    }
}
