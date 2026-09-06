package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aW3cTraceContextTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsAValidContextAndRejectsMissingOrForgedValues() throws Exception {
        A2aW3cTraceContext context = A2aW3cTraceContext.from(mapper.readTree("""
                {"%s":{"traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "baggage":"task.id=task-1"}}""".formatted(A2aW3cTraceContext.EXTENSION)));
        assertThat(context.traceparent()).contains("4bf92f3577b34da6a3ce929d0e0e4736");

        assertThatThrownBy(() -> A2aW3cTraceContext.from(mapper.createObjectNode()))
                .hasMessageContaining("Missing");
        assertThatThrownBy(() -> new A2aW3cTraceContext("invalid", null))
                .hasMessageContaining("traceparent");
    }
}
