package com.example.aifactory.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

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
