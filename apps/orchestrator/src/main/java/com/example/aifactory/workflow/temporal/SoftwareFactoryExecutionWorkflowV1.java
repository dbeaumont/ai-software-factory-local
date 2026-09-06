package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;
import java.util.Map;

/** Immutable public workflow type used by all post-cutover task admissions. */
@WorkflowInterface
public interface SoftwareFactoryExecutionWorkflowV1 {
    @WorkflowMethod(name = "SoftwareFactoryExecutionWorkflowV1")
    SoftwareFactoryWorkflow.Result run(SoftwareFactoryWorkflow.Request request);

    @SignalMethod(name = "approve")
    void approve(SoftwareFactoryWorkflow.ApprovalSignal signal);

    @SignalMethod(name = "cancel")
    void cancel(SoftwareFactoryWorkflow.CancellationSignal signal);

    @SignalMethod(name = "humanDecision")
    void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal);

    @SignalMethod(name = "a2aTaskUpdate")
    void a2aTaskUpdate(com.example.aifactory.a2a.A2aContracts.Notification notification);

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
}
