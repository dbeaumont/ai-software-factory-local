package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;
import java.util.Map;

/** Hierarchical-only workflow contract for every new task admission. */
@WorkflowInterface
public interface SoftwareFactoryExecutionWorkflowV2 {
    @WorkflowMethod(name = "SoftwareFactoryExecutionWorkflowV2")
    SoftwareFactoryWorkflow.Result run(Request request);

    @SignalMethod(name = "approve")
    void approve(SoftwareFactoryWorkflow.ApprovalSignal signal);

    @SignalMethod(name = "cancel")
    void cancel(SoftwareFactoryWorkflow.CancellationSignal signal);

    @SignalMethod(name = "humanDecision")
    void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal);

    @SignalMethod(name = "a2aTaskUpdate")
    void a2aTaskUpdate(A2aContracts.Notification notification);

    @QueryMethod(name = "status")
    String status();

    @QueryMethod(name = "dag")
    List<SoftwareFactoryWorkflow.DelegationView> dag();

    @QueryMethod(name = "budgets")
    Map<String, DelegationWorkflow.Budget> budgets();

    @QueryMethod(name = "evidence")
    List<String> evidence();

    @QueryMethod(name = "pendingEffects")
    List<SoftwareFactoryWorkflow.PendingEffectView> pendingEffects();

    /** The execution strategy is deliberately absent: V2 is hierarchical by contract. */
    record Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                   String requirementDigest, SoftwareFactoryWorkflow.SourceLocation sourceLocation,
                   SoftwareFactoryWorkflow.AttemptLineage attemptLineage) {
        public Request {
            if (requirementDigest == null || requirementDigest.isBlank()) {
                throw new IllegalArgumentException("Workflow requirement is required");
            }
            requirementDigest = requirementDigest.matches("[0-9a-f]{64}")
                    ? requirementDigest : TemporalIds.sha256(requirementDigest);
        }

        SoftwareFactoryWorkflow.Request hierarchicalRequest() {
            return new SoftwareFactoryWorkflow.Request(taskId, attemptId, repositoryId, sourceCommit,
                    requirementDigest, List.of(), null, List.of(), null, null, null, sourceLocation,
                    SoftwareFactoryWorkflow.WorkflowExecutionMode.HIERARCHICAL_ACTIVE, attemptLineage);
        }
    }
}
