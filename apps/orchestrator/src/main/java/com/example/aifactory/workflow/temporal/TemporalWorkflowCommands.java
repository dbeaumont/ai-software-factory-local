package com.example.aifactory.workflow.temporal;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
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
        TemporalPayloadGuard.requireSafeStart(options, request);
        SoftwareFactoryExecutionWorkflowV1 workflow = client.newWorkflowStub(
                SoftwareFactoryExecutionWorkflowV1.class, options);
        try {
            WorkflowExecution execution = WorkflowClient.start(workflow::run, request);
            return new ExecutionIdentity(execution.getWorkflowId(), execution.getRunId());
        } catch (WorkflowExecutionAlreadyStarted existing) {
            WorkflowExecution execution = existing.getExecution();
            return new ExecutionIdentity(execution.getWorkflowId(), execution.getRunId());
        }
    }

    void approve(String workflowId, SoftwareFactoryWorkflow.ApprovalSignal signal) {
        TemporalPayloadGuard.requireSafePayload(signal);
        signal(workflowId, workflow -> workflow.approve(signal));
    }

    void decide(String workflowId, SoftwareFactoryWorkflow.HumanDecisionSignal signal) {
        TemporalPayloadGuard.requireSafePayload(signal);
        signal(workflowId, workflow -> workflow.decide(signal));
    }

    void cancel(String workflowId, SoftwareFactoryWorkflow.CancellationSignal signal) {
        TemporalPayloadGuard.requireSafePayload(signal);
        signal(workflowId, workflow -> workflow.cancel(signal));
    }

    private void signal(String workflowId, java.util.function.Consumer<SoftwareFactoryExecutionWorkflowV1> signal) {
        try {
            signal.accept(client.newWorkflowStub(SoftwareFactoryExecutionWorkflowV1.class, workflowId));
        } catch (RuntimeException failure) {
            TemporalCommandConflictException.Reason reason = classifySignalFailure(failure);
            if (reason == TemporalCommandConflictException.Reason.WORKFLOW_ABSENT)
                throw new TemporalCommandConflictException(reason, "Temporal workflow does not exist", failure);
            if (reason == TemporalCommandConflictException.Reason.WORKFLOW_TERMINATED)
                throw new TemporalCommandConflictException(reason,
                        "Temporal workflow is no longer accepting commands", failure);
            throw failure;
        }
    }

    static TemporalCommandConflictException.Reason classifySignalFailure(RuntimeException failure) {
        String type = failure.getClass().getSimpleName();
        String message = String.valueOf(failure.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (type.contains("WorkflowNotFound") || message.contains("not found")) {
            return TemporalCommandConflictException.Reason.WORKFLOW_ABSENT;
        }
        if (message.contains("closed") || message.contains("completed") || message.contains("terminated")) {
            return TemporalCommandConflictException.Reason.WORKFLOW_TERMINATED;
        }
        return null;
    }

    record ExecutionIdentity(String workflowId, String runId) {
        ExecutionIdentity {
            if (workflowId == null || workflowId.isBlank() || runId == null || runId.isBlank()) {
                throw new IllegalStateException("Temporal did not return a complete execution identity");
            }
        }
    }
}
