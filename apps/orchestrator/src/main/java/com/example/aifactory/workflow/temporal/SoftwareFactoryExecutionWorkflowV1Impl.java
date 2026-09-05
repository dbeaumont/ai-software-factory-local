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
        SoftwareFactoryWorkflow.SourceLocation source = request == null ? null : request.sourceLocation();
        if (source == null) throw new IllegalArgumentException("Production workflow source location is required");
        SourceResolutionActivities activities = io.temporal.workflow.Workflow.newActivityStub(
                SourceResolutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.READ, source.contextTaskQueue()));
        String inputDigest = TemporalIds.sha256(String.join("\u0000", request.repositoryId(),
                source.repositoryUrl(), source.branch()));
        String idempotencyKey = TemporalIds.effectKey(request.taskId(), request.attemptId(), "source",
                "resolve", 1, inputDigest, inputDigest);
        SourceResolutionActivities.Result resolved = activities.resolve(new SourceResolutionActivities.Request(
                request.taskId(), request.attemptId(), request.repositoryId(), source.repositoryUrl(),
                source.branch(), idempotencyKey));
        if (!request.repositoryId().equals(resolved.repositoryId()) || !source.branch().equals(resolved.branch())) {
            throw new SecurityException("Resolved source attestation changed workflow identity");
        }
        return delegate.run(request.withResolvedSource(resolved.sourceCommit()));
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
