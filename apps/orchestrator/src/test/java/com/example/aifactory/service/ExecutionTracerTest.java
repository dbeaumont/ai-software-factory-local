package com.example.aifactory.service;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.example.aifactory.workflow.temporal.TemporalWorkerTracingInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTracerTest {
    @Test
    void createsAllRequiredSpanKindsWithCorrelationAttributes() {
        ObservationRegistry registry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public void onStop(Observation.Context context) { stopped.add(context); }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        ExecutionTracer tracer = new ExecutionTracer(registry);
        ExecutionIdentity identity = ExecutionIdentity.deterministic("task-1", "run-1", "node-1", "agent-1");

        Arrays.stream(ExecutionTracer.SpanKind.values()).forEach(kind ->
                tracer.trace(kind, identity, kind.name().toLowerCase(), () -> {}));

        assertThat(stopped).hasSize(5);
        assertThat(stopped).extracting(Observation.Context::getName).containsExactlyInAnyOrder(
                "ai.factory.workflow", "ai.factory.child.workflow", "ai.factory.activity",
                "ai.factory.llm", "ai.factory.mcp");
        assertThat(stopped).allSatisfy(context -> assertThat(context.getHighCardinalityKeyValues().stream()
                .map(value -> value.getKey()).toList()).contains(
                "trace_id", "run_id", "delegation_id", "agent_run_id"));
    }

    @Test
    void providesATemporalWorkerInterceptorForWorkflowChildAndActivitySpans() {
        assertThat(io.temporal.common.interceptors.WorkerInterceptorBase.class)
                .isAssignableFrom(TemporalWorkerTracingInterceptor.class);
    }
}
