package com.example.aifactory.workflow.temporal;

import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.util.List;
import java.util.Map;

/** V1 admission wrapper around the already replay-tested durable coordination implementation. */
public final class SoftwareFactoryExecutionWorkflowV1Impl implements SoftwareFactoryExecutionWorkflowV1 {
    private final SoftwareFactoryWorkflow delegate = new SoftwareFactoryWorkflowImpl();

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public SoftwareFactoryWorkflow.Result run(SoftwareFactoryWorkflow.Request request) {
        return delegate.run(request);
    }

    @Override public void approve(SoftwareFactoryWorkflow.ApprovalSignal signal) { delegate.approve(signal); }
    @Override public void cancel(SoftwareFactoryWorkflow.CancellationSignal signal) { delegate.cancel(signal); }
    @Override public void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal) { delegate.decide(signal); }
    @Override public String status() { return delegate.status(); }
    @Override public List<SoftwareFactoryWorkflow.DelegationView> dag() { return delegate.dag(); }
    @Override public Map<String, DelegationWorkflow.Budget> budgets() { return delegate.budgets(); }
    @Override public List<String> evidence() { return delegate.evidence(); }
    @Override public List<SoftwareFactoryWorkflow.PendingEffectView> pendingEffects() {
        return delegate.pendingEffects();
    }
}
