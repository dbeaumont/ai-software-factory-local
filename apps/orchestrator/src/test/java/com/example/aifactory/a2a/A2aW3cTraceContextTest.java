package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aW3cTraceContextTest {
    @Test
    void addsOnlyValidatedBoundedW3cFieldsToA2aMetadata() {
        A2aW3cTraceContext context = new A2aW3cTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-03",
                "task.id=AF-42,attempt.id=attempt-1");

        assertThat(context.addTo(Map.of("business", "kept")))
                .containsEntry("business", "kept")
                .extractingByKey(A2aW3cTraceContext.EXTENSION)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("traceparent", context.traceparent())
                .containsEntry("baggage", context.baggage());
    }

    @Test
    void rejectsForgedOrUnboundedContext() {
        assertThatThrownBy(() -> new A2aW3cTraceContext("not-a-traceparent", null))
                .hasMessageContaining("traceparent");
        assertThatThrownBy(() -> new A2aW3cTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "x\nforged=true"))
                .hasMessageContaining("baggage");
        assertThatThrownBy(() -> new A2aW3cTraceContext(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "a".repeat(1025)))
                .hasMessageContaining("baggage");
    }

    @Test
    void createsAValidContextWhenNoTelemetryPropagatorIsInstalled() {
        assertThat(A2aW3cTraceContext.captureOrCreate().traceparent())
                .matches("00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-01");
    }
}
