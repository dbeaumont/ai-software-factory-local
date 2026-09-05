package com.example.aifactory.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.util.Map;
import org.slf4j.MDC;

/** Creates consistently named observations that tracing bridges export as spans. */
@Component
public final class ExecutionTracer {
    private final ObservationRegistry registry;

    public ExecutionTracer(ObservationRegistry registry) {
        this.registry = registry;
    }

    public static ExecutionTracer noop() {
        return new ExecutionTracer(ObservationRegistry.NOOP);
    }

    public <T> T trace(SpanKind kind, ExecutionIdentity identity, String operation, Supplier<T> work) {
        if (kind == null || identity == null || operation == null || operation.isBlank() || work == null) {
            throw new IllegalArgumentException("Span context is incomplete");
        }
        Observation observation = Observation.createNotStarted(kind.observationName(), registry)
                .contextualName(operation)
                .lowCardinalityKeyValue("ai.kind", kind.name())
                .lowCardinalityKeyValue("ai.operation", operation)
                .highCardinalityKeyValue("trace_id", identity.traceId())
                .highCardinalityKeyValue("run_id", identity.runId())
                .highCardinalityKeyValue("delegation_id", identity.delegationId())
                .highCardinalityKeyValue("agent_run_id", identity.agentRunId());
        return observation.observe(work);
    }

    public void trace(SpanKind kind, ExecutionIdentity identity, String operation, Runnable work) {
        trace(kind, identity, operation, () -> {
            work.run();
            return null;
        });
    }

    public <T> T traceTemporal(SpanKind kind, ExecutionIdentity identity, String operation,
                               TemporalContext temporal, Supplier<T> work) {
        if (kind == null || identity == null || operation == null || operation.isBlank()
                || temporal == null || work == null) {
            throw new IllegalArgumentException("Temporal span context is incomplete");
        }
        Observation observation = Observation.createNotStarted(kind.observationName(), registry)
                .contextualName(operation)
                .lowCardinalityKeyValue("ai.kind", kind.name())
                .lowCardinalityKeyValue("temporal.namespace", temporal.namespace())
                .lowCardinalityKeyValue("temporal.task_queue", temporal.taskQueue())
                .lowCardinalityKeyValue("temporal.workflow.type", temporal.workflowType())
                .lowCardinalityKeyValue("temporal.activity.type", temporal.activityType())
                .highCardinalityKeyValue("ai.task.id", temporal.taskId())
                .highCardinalityKeyValue("ai.attempt.id", temporal.attemptId())
                .highCardinalityKeyValue("temporal.workflow.id", temporal.workflowId())
                .highCardinalityKeyValue("temporal.run.id", temporal.runId())
                .highCardinalityKeyValue("trace_id", identity.traceId());
        Map<String, String> previous = MDC.getCopyOfContextMap();
        temporal.mdc().forEach(MDC::put);
        try {
            return observation.observe(work);
        } finally {
            MDC.clear();
            if (previous != null) MDC.setContextMap(previous);
        }
    }

    public void traceTemporal(SpanKind kind, ExecutionIdentity identity, String operation,
                              TemporalContext temporal, Runnable work) {
        traceTemporal(kind, identity, operation, temporal, () -> {
            work.run();
            return null;
        });
    }

    public record TemporalContext(String namespace, String taskQueue, String workflowType, String activityType,
                                  String taskId, String attemptId, String workflowId, String runId) {
        public TemporalContext {
            namespace = bounded(namespace); taskQueue = bounded(taskQueue); workflowType = bounded(workflowType);
            activityType = bounded(activityType); taskId = correlation(taskId); attemptId = correlation(attemptId);
            workflowId = correlation(workflowId); runId = correlation(runId);
        }

        private Map<String, String> mdc() {
            return Map.of("temporal.namespace", namespace, "temporal.task_queue", taskQueue,
                    "temporal.workflow.type", workflowType, "temporal.activity.type", activityType,
                    "ai.task.id", taskId, "ai.attempt.id", attemptId,
                    "temporal.workflow.id", workflowId, "temporal.run.id", runId);
        }

        private static String bounded(String value) {
            return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") ? value : "unknown";
        }

        private static String correlation(String value) {
            return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,254}") ? value : "unknown";
        }
    }

    public enum SpanKind {
        WORKFLOW("ai.factory.workflow"),
        CHILD_WORKFLOW("ai.factory.child.workflow"),
        ACTIVITY("ai.factory.activity"),
        LLM("ai.factory.llm"),
        MCP("ai.factory.mcp");

        private final String observationName;

        SpanKind(String observationName) { this.observationName = observationName; }

        public String observationName() { return observationName; }
    }
}
