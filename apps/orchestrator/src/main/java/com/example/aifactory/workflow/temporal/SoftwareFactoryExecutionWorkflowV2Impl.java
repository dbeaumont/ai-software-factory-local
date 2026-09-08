package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.util.List;
import java.util.Map;

/** V2 boundary: hierarchical execution is implicit and no legacy mode is persisted in its input. */
public final class SoftwareFactoryExecutionWorkflowV2Impl implements SoftwareFactoryExecutionWorkflowV2 {
    private final SoftwareFactoryExecutionWorkflowV1Impl delegate = new SoftwareFactoryExecutionWorkflowV1Impl();

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public SoftwareFactoryWorkflow.Result run(Request request) {
        if (request == null) throw new IllegalArgumentException("Production workflow request is required");
        return delegate.run(request.hierarchicalRequest());
    }

    @Override public void approve(SoftwareFactoryWorkflow.ApprovalSignal signal) { delegate.approve(signal); }
    @Override public void cancel(SoftwareFactoryWorkflow.CancellationSignal signal) { delegate.cancel(signal); }
    @Override public void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal) { delegate.decide(signal); }
    @Override public void a2aTaskUpdate(A2aContracts.Notification notification) { delegate.a2aTaskUpdate(notification); }
    @Override public String status() { return delegate.status(); }
    @Override public List<SoftwareFactoryWorkflow.DelegationView> dag() { return delegate.dag(); }
    @Override public Map<String, DelegationWorkflow.Budget> budgets() { return delegate.budgets(); }
    @Override public List<String> evidence() { return delegate.evidence(); }
    @Override public List<SoftwareFactoryWorkflow.PendingEffectView> pendingEffects() {
        return delegate.pendingEffects();
    }
}
