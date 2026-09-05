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
import org.slf4j.MDC;

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
        assertThat(stopped).allSatisfy(context -> assertThat(context.getAllKeyValues().stream()
                .map(value -> value.getKey()).toList()).doesNotContain(
                "gen_ai.prompt", "gen_ai.completion", "ai.result", "ai.evidence"));
    }

    @Test
    void providesATemporalWorkerInterceptorForWorkflowChildAndActivitySpans() {
        assertThat(io.temporal.common.interceptors.WorkerInterceptorBase.class)
                .isAssignableFrom(TemporalWorkerTracingInterceptor.class);
    }

    @Test
    void keepsUnboundedTemporalIdentifiersOutOfMetricTagsAndAddsThemToTraceAndLogCorrelation() {
        ObservationRegistry registry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override public void onStop(Observation.Context context) { stopped.add(context); }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        ExecutionTracer tracer = new ExecutionTracer(registry);
        ExecutionIdentity identity = ExecutionIdentity.deterministic("task-1", "run-1", "node-1", "agent-1");
        ExecutionTracer.TemporalContext temporal = new ExecutionTracer.TemporalContext(
                "ai-factory-local", "ai-factory-sandbox", "SoftwareFactoryExecutionWorkflowV1",
                "ExecutePipelineStep", "task-1", "pipeline-1", "ai-factory/task-1/pipeline-1", "run-uuid");

        tracer.traceTemporal(ExecutionTracer.SpanKind.ACTIVITY, identity, "ExecutePipelineStep", temporal, () -> {
            assertThat(MDC.get("ai.task.id")).isEqualTo("task-1");
            assertThat(MDC.get("temporal.workflow.id")).isEqualTo("ai-factory/task-1/pipeline-1");
        });

        Observation.Context context = stopped.getFirst();
        assertThat(context.getLowCardinalityKeyValues().stream().map(value -> value.getKey()).toList())
                .containsExactlyInAnyOrder("ai.kind", "temporal.namespace", "temporal.task_queue",
                        "temporal.workflow.type", "temporal.activity.type");
        assertThat(context.getHighCardinalityKeyValues().stream().map(value -> value.getKey()).toList())
                .contains("ai.task.id", "ai.attempt.id", "temporal.workflow.id", "temporal.run.id");
        assertThat(MDC.get("ai.task.id")).isNull();
    }

    @Test
    void extractsTaskAndAttemptOnlyFromCanonicalRootWorkflowIds() {
        assertThat(TemporalWorkerTracingInterceptor.correlation("ai-factory/task-1/pipeline-3"))
                .isEqualTo(new TemporalWorkerTracingInterceptor.Correlation("task-1", "pipeline-3"));
        assertThat(TemporalWorkerTracingInterceptor.correlation("untrusted/free/form/value"))
                .isEqualTo(new TemporalWorkerTracingInterceptor.Correlation("unknown", "unknown"));
    }
}
