package com.example.aifactory.workflow.temporal;

import io.temporal.api.common.v1.Payload;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalTraceContextPropagatorTest {
    private final TemporalTraceContextPropagator propagator = new TemporalTraceContextPropagator();

    @Test
    void acceptsBoundedW3cHeaders() {
        Object context = propagator.deserializeContext(Map.of(
                "traceparent", payload("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"),
                "baggage", payload("task.id=AF-0042,attempt.id=attempt-1")));

        assertThat(context).isInstanceOf(io.opentelemetry.context.Context.class);
    }

    @Test
    void rejectsMalformedOrInjectedHeaders() {
        assertThatThrownBy(() -> propagator.deserializeContext(Map.of(
                "traceparent", payload("not-a-traceparent"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> propagator.deserializeContext(Map.of(
                "baggage", payload("task.id=AF-0042\nforged=true"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAllZeroTraceAndOversizedBaggage() {
        assertThatThrownBy(() -> propagator.deserializeContext(Map.of("traceparent",
                payload("00-00000000000000000000000000000000-0000000000000000-01"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> propagator.deserializeContext(Map.of("baggage", payload("a".repeat(1025)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Payload payload(String value) {
        return Payload.newBuilder().setData(com.google.protobuf.ByteString.copyFromUtf8(value)).build();
    }
}
