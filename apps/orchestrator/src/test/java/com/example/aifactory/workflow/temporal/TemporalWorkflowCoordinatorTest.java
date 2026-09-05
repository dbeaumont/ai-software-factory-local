package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.ScmDeliveryClientProperties;
import com.example.aifactory.config.TemporalProperties;
import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.HumanDecisionResponse;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.service.PipelineStepContracts;
import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalWorkflowCoordinatorTest {
    private final TemporalWorkflowCommands commands = mock(TemporalWorkflowCommands.class);
    private final TemporalProperties properties = properties();
    private final TemporalWorkflowCoordinator coordinator = new TemporalWorkflowCoordinator(commands, properties,
            new ScmDeliveryClientProperties(true, "scm-delivery-mcp", "operator@example.test", "x".repeat(32)));

    @Test
    void startsV1OnTheWorkflowQueueAndProjectsItsRunIdentity() {
        TaskState task = task();
        String workflowId = TemporalIds.workflow(task.id, PipelineStepContracts.INITIAL_ATTEMPT_ID);
        when(commands.start(any(), any())).thenReturn(
                new TemporalWorkflowCommands.ExecutionIdentity(workflowId, "run-123"));
        ArgumentCaptor<WorkflowOptions> options = ArgumentCaptor.forClass(WorkflowOptions.class);
        ArgumentCaptor<SoftwareFactoryWorkflow.Request> request =
                ArgumentCaptor.forClass(SoftwareFactoryWorkflow.Request.class);

        coordinator.start(task);

        verify(commands).start(options.capture(), request.capture());
        assertThat(options.getValue().getWorkflowId()).isEqualTo(workflowId);
        assertThat(options.getValue().getTaskQueue()).isEqualTo("ai-factory-workflows");
        assertThat(request.getValue().attemptId()).isEqualTo("pipeline-1");
        assertThat(request.getValue().repositoryId()).isEqualTo("customer-api");
        assertThat(request.getValue().sourceCommit()).isEqualTo("UNRESOLVED");
        assertThat(request.getValue().sourceLocation().repositoryUrl()).isEqualTo(task.request.repositoryUrl());
        assertThat(request.getValue().sourceLocation().taskQueues()).isEqualTo(properties.taskQueues());
        assertThat(task.executionMode).isEqualTo("PIPELINE");
        assertThat(task.workflowRunId).isEqualTo("run-123");
        assertThat(task.dagVersion).isEqualTo("build-1");
    }

    @Test
    void signalsOnlyTheManifestBoundApproval() {
        TaskState task = task();
        task.pendingEffect = new PendingEffect("scm.create_draft_pull_request", Map.of(), "Create draft PR",
                "ALLOW", true, "a".repeat(64), "evidence://manifest/a", "b".repeat(64));
        ArgumentCaptor<SoftwareFactoryWorkflow.ApprovalSignal> signal =
                ArgumentCaptor.forClass(SoftwareFactoryWorkflow.ApprovalSignal.class);

        coordinator.resumeAfterApproval(task);

        verify(commands).approve(org.mockito.ArgumentMatchers.eq(
                TemporalIds.workflow(task.id, PipelineStepContracts.INITIAL_ATTEMPT_ID)), signal.capture());
        assertThat(signal.getValue().manifestId()).isEqualTo("a".repeat(64));
        assertThat(signal.getValue().manifestDigest()).isEqualTo("b".repeat(64));
        assertThat(signal.getValue().approver()).isEqualTo("operator@example.test");
        assertThat(signal.getValue().decision()).isEqualTo("APPROVE");
    }

    @Test
    void rejectsAnApprovalWithoutTheProjectedManifest() {
        assertThatThrownBy(() -> coordinator.resumeAfterApproval(task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest-bound");
    }

    @Test
    void validatesAndSignalsAHumanDecisionWithoutMutatingTheProjection() {
        TaskState task = task();
        String digest = "d".repeat(64);
        task.recordHumanAction("architecture-1", "contradiction-1", "ARCHITECTURE", "Choose API", digest,
                "PENDING", java.util.List.of(
                        new com.example.aifactory.model.TaskView.DecisionOptionView(
                                "REST", "REST", "Expose an HTTP API", true)));
        ArgumentCaptor<SoftwareFactoryWorkflow.HumanDecisionSignal> signal =
                ArgumentCaptor.forClass(SoftwareFactoryWorkflow.HumanDecisionSignal.class);

        coordinator.answerHumanDecision(task, "architecture-1",
                new HumanDecisionResponse("REST", digest, "architect@example.test", "ARCHITECTURE"));

        verify(commands).decide(org.mockito.ArgumentMatchers.eq(
                TemporalIds.workflow(task.id, PipelineStepContracts.INITIAL_ATTEMPT_ID)), signal.capture());
        assertThat(signal.getValue().decisionId()).isEqualTo("architecture-1");
        assertThat(signal.getValue().objectDigest()).isEqualTo(digest);
        assertThat(signal.getValue().actorRole()).isEqualTo("ARCHITECTURE");
        assertThat(task.humanActions.get("architecture-1").status()).isEqualTo("PENDING");
    }

    @Test
    void refusesAnUnauthorizedDecisionBeforeSignallingTemporal() {
        TaskState task = task();
        String digest = "d".repeat(64);
        task.recordHumanAction("architecture-1", "contradiction-1", "ARCHITECTURE", "Choose API", digest,
                "PENDING");

        assertThatThrownBy(() -> coordinator.answerHumanDecision(task, "architecture-1",
                new HumanDecisionResponse("REST", digest, "reviewer", "SECURITY")))
                .isInstanceOf(SecurityException.class);
        org.mockito.Mockito.verify(commands, org.mockito.Mockito.never()).decide(any(), any());
    }

    private static TaskState task() {
        return new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
    }

    private static TemporalProperties properties() {
        Map<String, String> queues = Map.of(
                "workflow", "ai-factory-workflows", "context", "ai-factory-context",
                "llm", "ai-factory-llm", "sandbox", "ai-factory-sandbox",
                "assurance", "ai-factory-assurance", "evidence", "ai-factory-evidence",
                "scm", "ai-factory-scm");
        return new TemporalProperties("temporal:7233", "ai-factory-local", Duration.ofDays(7),
                "orchestrator", "build-1", queues, TemporalProperties.Capacity.defaults(),
                new TemporalProperties.Security(false, "", "", "", ""));
    }
}
