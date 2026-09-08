package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.util.List;
import java.util.Map;

/** V2 boundary: hierarchical execution is implicit and no legacy mode is persisted in its input. */
public final class SoftwareFactoryExecutionWorkflowV2Impl implements SoftwareFactoryExecutionWorkflowV2 {
    private final SoftwareFactoryExecutionWorkflowV1Impl delegate = new SoftwareFactoryExecutionWorkflowV1Impl();
    private String phase = "CREATED";
    private HierarchicalRoutingActivities.Decision routingDecision;

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public SoftwareFactoryWorkflow.Result run(Request request) {
        if (request == null) throw new IllegalArgumentException("Production workflow request is required");
        SoftwareFactoryWorkflow.SourceLocation source = request.sourceLocation();
        if (source == null) throw new IllegalArgumentException("Production workflow source location is required");
        SourceResolutionActivities sourceActivities = io.temporal.workflow.Workflow.newActivityStub(
                SourceResolutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.READ, source.contextTaskQueue()));
        String inputDigest = TemporalIds.sha256(String.join("\u0000", request.repositoryId(),
                source.repositoryUrl(), source.branch()));
        String idempotencyKey = TemporalIds.effectKey(request.taskId(), request.attemptId(), "source",
                "resolve", 1, inputDigest, inputDigest);
        SourceResolutionActivities.Result resolved = sourceActivities.resolve(new SourceResolutionActivities.Request(
                request.taskId(), request.attemptId(), request.repositoryId(), source.repositoryUrl(),
                source.branch(), idempotencyKey));
        if (!request.repositoryId().equals(resolved.repositoryId()) || !source.branch().equals(resolved.branch())) {
            throw new SecurityException("Resolved source attestation changed workflow identity");
        }
        phase = "SOURCE_RESOLVED";
        HierarchicalRoutingActivities routing = io.temporal.workflow.Workflow.newActivityStub(
                HierarchicalRoutingActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.READ, source.contextTaskQueue()));
        routingDecision = routing.decide(new HierarchicalRoutingActivities.Request(
                request.taskId(), request.repositoryId(), resolved.sourceCommit(), request.routingFacts()));
        phase = "ROUTED:" + routingDecision.selectedPath();
        if ("HUMAN_TRIAGE".equals(routingDecision.selectedPath())) {
            return new SoftwareFactoryWorkflow.Result(request.taskId(), request.attemptId(),
                    resolved.sourceCommit(), "HUMAN_TRIAGE", List.of(
                    "SOURCE_RESOLVED:" + resolved.sourceCommit(),
                    "ROUTING_DECIDED:" + routingDecision.decisionId() + ":HUMAN_TRIAGE"),
                    List.of(), Map.of(), null, null, null, null);
        }
        if (!List.of("SHORT_CODE_PATH", "HIERARCHICAL_PATH").contains(routingDecision.selectedPath())) {
            throw new SecurityException("Routing policy returned an unsupported execution path");
        }
        SoftwareFactoryWorkflow.Result result = delegate.run(request.hierarchicalRequest());
        phase = result.status();
        return result;
    }

    @Override public void approve(SoftwareFactoryWorkflow.ApprovalSignal signal) { delegate.approve(signal); }
    @Override public void cancel(SoftwareFactoryWorkflow.CancellationSignal signal) { delegate.cancel(signal); }
    @Override public void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal) { delegate.decide(signal); }
    @Override public void a2aTaskUpdate(A2aContracts.Notification notification) { delegate.a2aTaskUpdate(notification); }
    @Override public String status() {
        return phase.startsWith("ROUTED:") || "CREATED".equals(phase) ? delegate.status() : phase;
    }
    @Override public List<SoftwareFactoryWorkflow.DelegationView> dag() { return delegate.dag(); }
    @Override public Map<String, DelegationWorkflow.Budget> budgets() { return delegate.budgets(); }
    @Override public List<String> evidence() { return delegate.evidence(); }
    @Override public List<SoftwareFactoryWorkflow.PendingEffectView> pendingEffects() {
        return delegate.pendingEffects();
    }
}
