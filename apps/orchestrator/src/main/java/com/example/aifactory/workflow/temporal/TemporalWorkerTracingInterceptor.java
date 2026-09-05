package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.ExecutionIdentity;
import com.example.aifactory.service.ExecutionTracer;
import com.example.aifactory.service.TaskQueueMetrics;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Worker interceptor that creates root/child workflow and Activity observations outside business payloads. */
@Component
public final class TemporalWorkerTracingInterceptor extends WorkerInterceptorBase {
    private final ExecutionTracer tracer;
    private final TaskQueueMetrics queueMetrics;

    public TemporalWorkerTracingInterceptor(ExecutionTracer tracer) {
        this(tracer, TaskQueueMetrics.noop());
    }

    @Autowired
    public TemporalWorkerTracingInterceptor(ExecutionTracer tracer, TaskQueueMetrics queueMetrics) {
        this.tracer = tracer;
        this.queueMetrics = queueMetrics;
    }

    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
        return new WorkflowInboundCallsInterceptorBase(next) {
            @Override
            public WorkflowOutput execute(WorkflowInput input) {
                // A replay rebuilds workflow state and must not emit duplicate telemetry.
                if (WorkflowUnsafe.isReplaying()) {
                    return super.execute(input);
                }
                WorkflowInfo info = Workflow.getInfo();
                ExecutionTracer.SpanKind kind = info.getParentWorkflowId().isPresent()
                        ? ExecutionTracer.SpanKind.CHILD_WORKFLOW : ExecutionTracer.SpanKind.WORKFLOW;
                ExecutionIdentity identity = ExecutionIdentity.deterministic(
                        bounded(info.getWorkflowId()), bounded(info.getRunId()), bounded(info.getWorkflowId()),
                        bounded(info.getWorkflowType()));
                Correlation correlation = correlation(info.getRootWorkflowId().orElse(info.getWorkflowId()));
                try {
                    return tracer.traceTemporal(kind, identity, info.getWorkflowType(),
                            new ExecutionTracer.TemporalContext(info.getNamespace(), info.getTaskQueue(),
                                    info.getWorkflowType(), "none", correlation.taskId(), correlation.attemptId(),
                                    info.getWorkflowId(), info.getRunId()), () -> super.execute(input));
                } catch (RuntimeException failure) {
                    queueMetrics.recordWorkflowFailure(info.getTaskQueue(), failure);
                    throw failure;
                }
            }
        };
    }

    @Override
    public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
        return new ActivityInboundCallsInterceptorBase(next) {
            private ActivityExecutionContext executionContext;

            @Override
            public void init(ActivityExecutionContext context) {
                this.executionContext = context;
                super.init(context);
            }

            @Override
            public ActivityOutput execute(ActivityInput input) {
                var info = executionContext.getInfo();
                ExecutionIdentity identity = ExecutionIdentity.deterministic(
                        bounded(info.getWorkflowId()), bounded(info.getWorkflowRunId()),
                        bounded(info.getActivityId()), bounded(info.getActivityRunId()));
                try (TaskQueueMetrics.Lease ignored = queueMetrics.start(info.getActivityTaskQueue(),
                        info.getCurrentAttemptScheduledTimestamp(), info.getStartedTimestamp(), info.getAttempt())) {
                    Correlation correlation = correlation(info.getWorkflowId());
                    return tracer.traceTemporal(ExecutionTracer.SpanKind.ACTIVITY, identity,
                            info.getActivityType(), new ExecutionTracer.TemporalContext(
                                    info.getNamespace(), info.getActivityTaskQueue(), info.getWorkflowType(),
                                    info.getActivityType(), correlation.taskId(), correlation.attemptId(),
                                    info.getWorkflowId(), info.getWorkflowRunId()), () -> super.execute(input));
                }
            }
        };
    }

    private static String bounded(String value) {
        if (value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) return value;
        return "id-" + Integer.toUnsignedString(value == null ? 0 : value.hashCode(), 16);
    }

    public static Correlation correlation(String workflowId) {
        if (workflowId != null && workflowId.startsWith("ai-factory/")) {
            String[] parts = workflowId.split("/", -1);
            if (parts.length == 3 && parts[1].matches("[A-Za-z0-9_-]{1,64}")
                    && parts[2].matches("[A-Za-z0-9_-]{1,128}")) {
                return new Correlation(parts[1], parts[2]);
            }
        }
        return new Correlation("unknown", "unknown");
    }

    public record Correlation(String taskId, String attemptId) {}
}
