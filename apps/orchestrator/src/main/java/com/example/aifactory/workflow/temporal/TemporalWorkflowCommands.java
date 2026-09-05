package com.example.aifactory.workflow.temporal;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;

/** Thin SDK boundary kept separate so application command mapping can be tested without a Temporal server. */
@Component
final class TemporalWorkflowCommands {
    private final WorkflowClient client;

    TemporalWorkflowCommands(WorkflowClient client) {
        this.client = client;
    }

    ExecutionIdentity start(WorkflowOptions options, SoftwareFactoryWorkflow.Request request) {
        SoftwareFactoryExecutionWorkflowV1 workflow = client.newWorkflowStub(
                SoftwareFactoryExecutionWorkflowV1.class, options);
        WorkflowExecution execution = WorkflowClient.start(workflow::run, request);
        return new ExecutionIdentity(execution.getWorkflowId(), execution.getRunId());
    }

    void approve(String workflowId, SoftwareFactoryWorkflow.ApprovalSignal signal) {
        client.newWorkflowStub(SoftwareFactoryExecutionWorkflowV1.class, workflowId).approve(signal);
    }

    void decide(String workflowId, SoftwareFactoryWorkflow.HumanDecisionSignal signal) {
        client.newWorkflowStub(SoftwareFactoryExecutionWorkflowV1.class, workflowId).decide(signal);
    }

    record ExecutionIdentity(String workflowId, String runId) {
        ExecutionIdentity {
            if (workflowId == null || workflowId.isBlank() || runId == null || runId.isBlank()) {
                throw new IllegalStateException("Temporal did not return a complete execution identity");
            }
        }
    }
}
