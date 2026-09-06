package com.example.aifactory.agentruntime;

import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

/** High-cardinality identifiers are attached to traces and logs only, never to metric tags. */
final class A2aTelemetryCorrelation implements AutoCloseable {
    private static final java.util.List<String> MDC_KEYS = java.util.List.of(
            "task.id", "workflow.id", "message.id");
    private final java.util.Map<String, String> previous;

    private A2aTelemetryCorrelation(String taskId, String workflowId, String messageId) {
        previous = new java.util.LinkedHashMap<>();
        put("task.id", taskId);
        put("workflow.id", workflowId);
        put("message.id", messageId);
        Span span = Span.current();
        span.setAttribute("ai_factory.task.id", taskId);
        span.setAttribute("ai_factory.workflow.id", workflowId);
        span.setAttribute("messaging.message.id", messageId);
    }

    static A2aTelemetryCorrelation open(String taskId, String workflowId, String messageId) {
        return new A2aTelemetryCorrelation(require(taskId), require(workflowId), require(messageId));
    }

    private void put(String key, String value) {
        previous.put(key, MDC.get(key));
        MDC.put(key, value);
    }

    @Override public void close() {
        MDC_KEYS.forEach(key -> {
            String value = previous.get(key);
            if (value == null) MDC.remove(key); else MDC.put(key, value);
        });
    }

    private static String require(String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new IllegalArgumentException("A2A correlation identifier is invalid");
        }
        return value;
    }
}
