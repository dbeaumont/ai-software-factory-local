package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.ExecutionIdentity;
import com.example.aifactory.service.ExecutionTracer;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptor;
import io.temporal.common.interceptors.WorkflowInboundCallsInterceptorBase;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInfo;
import org.springframework.stereotype.Component;

/** Worker interceptor that creates root/child workflow and Activity observations outside business payloads. */
@Component
public final class TemporalWorkerTracingInterceptor extends WorkerInterceptorBase {
    private final ExecutionTracer tracer;

    public TemporalWorkerTracingInterceptor(ExecutionTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
        return new WorkflowInboundCallsInterceptorBase(next) {
            @Override
            public WorkflowOutput execute(WorkflowInput input) {
                WorkflowInfo info = Workflow.getInfo();
                ExecutionTracer.SpanKind kind = info.getParentWorkflowId().isPresent()
                        ? ExecutionTracer.SpanKind.CHILD_WORKFLOW : ExecutionTracer.SpanKind.WORKFLOW;
                ExecutionIdentity identity = ExecutionIdentity.deterministic(
                        bounded(info.getWorkflowId()), bounded(info.getRunId()), bounded(info.getWorkflowId()),
                        bounded(info.getWorkflowType()));
                return tracer.trace(kind, identity, info.getWorkflowType(), () -> super.execute(input));
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
                return tracer.trace(ExecutionTracer.SpanKind.ACTIVITY, identity, info.getActivityType(),
                        () -> super.execute(input));
            }
        };
    }

    private static String bounded(String value) {
        if (value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) return value;
        return "id-" + Integer.toUnsignedString(value == null ? 0 : value.hashCode(), 16);
    }
}
