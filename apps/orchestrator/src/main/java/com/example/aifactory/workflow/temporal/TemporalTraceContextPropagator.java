package com.example.aifactory.workflow.temporal;

import com.google.protobuf.ByteString;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Strict W3C trace context bridge for workflow, child-workflow and activity headers. */
@Component
public final class TemporalTraceContextPropagator implements ContextPropagator {
    private static final Pattern TRACEPARENT = Pattern.compile(
            "00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}");
    private static final Pattern BAGGAGE = Pattern.compile("[\\x20-\\x7e]{1,1024}");
    private static final List<String> KEYS = List.of("traceparent", "baggage");
    private static final TextMapSetter<Map<String, String>> SETTER = Map::put;
    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
        @Override public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
    };
    private final ThreadLocal<Scope> activeScope = new ThreadLocal<>();

    @Override public String getName() { return "ai-factory-w3c"; }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
        Map<String, String> carrier = new LinkedHashMap<>();
        Context source = context instanceof Context candidate ? candidate : Context.current();
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(source, carrier, SETTER);
        validate(carrier);
        Map<String, Payload> serialized = new LinkedHashMap<>();
        carrier.forEach((key, value) -> {
            if (KEYS.contains(key)) {
                serialized.put(key, Payload.newBuilder()
                        .putMetadata("encoding", ByteString.copyFromUtf8("binary/plain"))
                        .setData(ByteString.copyFrom(value, StandardCharsets.UTF_8)).build());
            }
        });
        return Map.copyOf(serialized);
    }

    @Override
    public Object deserializeContext(Map<String, Payload> context) {
        Map<String, String> carrier = new LinkedHashMap<>();
        if (context != null) {
            context.forEach((key, payload) -> {
                if (KEYS.contains(key) && payload != null) carrier.put(key, payload.getData().toStringUtf8());
            });
        }
        validate(carrier);
        return GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), carrier, GETTER);
    }

    @Override public Object getCurrentContext() { return Context.current(); }

    @Override
    public void setCurrentContext(Object context) {
        Scope previous = activeScope.get();
        if (previous != null) previous.close();
        if (context instanceof Context propagated) {
            activeScope.set(propagated.makeCurrent());
        } else {
            activeScope.remove();
        }
    }

    static void validate(Map<String, String> carrier) {
        String traceparent = carrier.get("traceparent");
        if (traceparent != null && !TRACEPARENT.matcher(traceparent).matches()) {
            throw new IllegalArgumentException("Temporal traceparent is invalid");
        }
        String baggage = carrier.get("baggage");
        if (baggage != null && !BAGGAGE.matcher(baggage).matches()) {
            throw new IllegalArgumentException("Temporal baggage is invalid or too large");
        }
    }
}
