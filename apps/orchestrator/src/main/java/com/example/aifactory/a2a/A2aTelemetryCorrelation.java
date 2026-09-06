package com.example.aifactory.a2a;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

/** Correlates A2A work in traces and logs without exposing identifiers as metric dimensions. */
public final class A2aTelemetryCorrelation implements AutoCloseable {
    private static final java.util.List<String> MDC_KEYS = java.util.List.of(
            "task.id", "workflow.id", "message.id");
    private final java.util.Map<String, String> previous = new java.util.LinkedHashMap<>();

    private A2aTelemetryCorrelation(A2aExecutionContext execution, String messageId) {
        put("task.id", execution.taskId());
        put("workflow.id", execution.workflowId());
        put("message.id", require(messageId));
        Span.current().setAttribute("ai_factory.task.id", execution.taskId())
                .setAttribute("ai_factory.workflow.id", execution.workflowId())
                .setAttribute("messaging.message.id", messageId);
    }

    public static A2aTelemetryCorrelation open(A2aExecutionContext execution, String messageId) {
        if (execution == null) throw new IllegalArgumentException("A2A execution correlation is missing");
        return new A2aTelemetryCorrelation(execution, messageId);
    }

    private void put(String key, String value) { previous.put(key, MDC.get(key)); MDC.put(key, value); }

    @Override public void close() {
        MDC_KEYS.forEach(key -> {
            String value = previous.get(key);
            if (value == null) MDC.remove(key); else MDC.put(key, value);
        });
    }

    private static String require(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("A2A message correlation is invalid");
        }
        return value;
    }
}
