package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.ScmDeliveryClientProperties;
import com.example.aifactory.config.TemporalProperties;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.HumanDecisionResponse;
import com.example.aifactory.service.PipelineStepContracts;
import com.example.aifactory.service.ScmDeliveryGateway;
import com.example.aifactory.workflow.WorkflowCoordinator;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Sole production implementation of the application workflow command port. */
@Component
public final class TemporalWorkflowCoordinator implements WorkflowCoordinator {
    static final long PIPELINE_MAX_TOKENS = 100_000;
    static final long PIPELINE_MAX_COST_MICROS = 10_000_000;
    static final int PIPELINE_MAX_TURNS = 100;

    private final TemporalWorkflowCommands commands;
    private final TemporalProperties properties;
    private final ScmDeliveryClientProperties scm;

    TemporalWorkflowCoordinator(TemporalWorkflowCommands commands, TemporalProperties properties,
                                ScmDeliveryClientProperties scm) {
        this.commands = commands;
        this.properties = properties;
        this.scm = scm;
    }

    @Override
    public void start(TaskState task) {
        requireTask(task);
        String attemptId = PipelineStepContracts.INITIAL_ATTEMPT_ID;
        String workflowId = TemporalIds.workflow(task.id, attemptId);
        SoftwareFactoryWorkflow.SourceLocation source = new SoftwareFactoryWorkflow.SourceLocation(
                task.request.repositoryUrl(), task.request.effectiveBranch(),
                properties.taskQueues().get("context"), properties.taskQueues());
        SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                task.id, attemptId, ScmDeliveryGateway.repositoryId(task.request.repositoryUrl()),
                PipelineStepContracts.UNRESOLVED_SOURCE_COMMIT, task.request.requirement(), List.of(), null,
                List.of(), null, null, null, source, SoftwareFactoryWorkflow.WorkflowExecutionMode.PIPELINE);
        TemporalWorkflowCommands.ExecutionIdentity execution = commands.start(WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(properties.taskQueues().get("workflow"))
                .build(), request);
        if (!workflowId.equals(execution.workflowId())) {
            throw new SecurityException("Temporal started an unexpected workflow identity");
        }
        task.bindExecution("PIPELINE", execution.runId(), properties.buildId(),
                PIPELINE_MAX_TOKENS, PIPELINE_MAX_COST_MICROS, PIPELINE_MAX_TURNS);
    }

    @Override
    public void resumeAfterApproval(TaskState task) {
        requireTask(task);
        if (task.pendingEffect == null || task.pendingEffect.manifestId() == null
                || task.pendingEffect.manifestDigest() == null) {
            throw new IllegalStateException("A manifest-bound human approval is required");
        }
        String attemptId = PipelineStepContracts.INITIAL_ATTEMPT_ID;
        commands.approve(TemporalIds.workflow(task.id, attemptId), new SoftwareFactoryWorkflow.ApprovalSignal(
                task.id, attemptId, task.pendingEffect.manifestId(), task.pendingEffect.manifestDigest(),
                "APPROVE", scm.approver(), Instant.now().toString()));
    }

    @Override
    public void answerHumanDecision(TaskState task, String requestId, HumanDecisionResponse response) {
        requireTask(task);
        if (response == null) throw new IllegalArgumentException("Human decision response is required");
        task.requireHumanActionAnswer(requestId, response.decision(), response.objectDigest(),
                response.actor(), response.actorRole());
        String attemptId = PipelineStepContracts.INITIAL_ATTEMPT_ID;
        commands.decide(TemporalIds.workflow(task.id, attemptId), new SoftwareFactoryWorkflow.HumanDecisionSignal(
                task.id, attemptId, requestId, response.decision(), response.objectDigest(), response.actor(),
                response.actorRole(), Instant.now().toString()));
    }

    private static void requireTask(TaskState task) {
        if (task == null || task.request == null) throw new IllegalArgumentException("Task is required");
    }
}
