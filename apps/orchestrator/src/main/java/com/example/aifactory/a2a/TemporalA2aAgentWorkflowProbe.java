package com.example.aifactory.a2a;

import com.example.aifactory.config.TemporalProperties;
import io.grpc.Deadline;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Temporal SDK boundary used by operator-side A2A retry reconciliation. */
@Component
public final class TemporalA2aAgentWorkflowProbe implements A2aAgentWorkflowProbe {
    private final WorkflowServiceStubs temporal;
    private final String namespace;

    public TemporalA2aAgentWorkflowProbe(WorkflowServiceStubs temporal, TemporalProperties properties) {
        this.temporal = temporal;
        this.namespace = properties.namespace();
    }

    @Override
    public WorkflowExecutionStatus status(String agentRole, String a2aTaskId, Duration timeout) {
        if (agentRole == null || !agentRole.matches("[a-z][a-z0-9-]{1,63}")
                || a2aTaskId == null || a2aTaskId.isBlank()) {
            throw new IllegalArgumentException("A2A agent workflow identity is invalid");
        }
        String workflowId = "a2a-agent-task-v1/" + agentRole + '/' + a2aTaskId;
        return temporal.blockingStub()
                .withDeadline(Deadline.after(timeout.toMillis(), TimeUnit.MILLISECONDS))
                .describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                        .setNamespace(namespace)
                        .setExecution(WorkflowExecution.newBuilder().setWorkflowId(workflowId))
                        .build())
                .getWorkflowExecutionInfo().getStatus();
    }
}
