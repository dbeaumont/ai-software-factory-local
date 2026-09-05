package com.example.aifactory.workflow.temporal;

import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** V1 admission wrapper around the already replay-tested durable coordination implementation. */
public final class SoftwareFactoryExecutionWorkflowV1Impl implements SoftwareFactoryExecutionWorkflowV1 {
    private final SoftwareFactoryWorkflow delegate = new SoftwareFactoryWorkflowImpl();
    private String phase = "CREATED";
    private final Map<String, com.example.aifactory.service.PipelineStepContracts.ArtifactReference> artifacts =
            new LinkedHashMap<>();

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
        phase = "SOURCE_RESOLVED";
        PipelineExecutionActivities context = pipeline(source, "context", TemporalActivityPolicies.Kind.READ);
        context.bindSource(new PipelineExecutionActivities.SourceBinding(request.taskId(), request.attemptId(),
                request.repositoryId(), resolved.sourceCommit(), resolved.workspace(), resolved.attestationDigest()));
        runStep(source, request, resolved, "plan", TemporalActivityPolicies.Kind.LLM,
                Map.of("requirement", TemporalIds.sha256(request.requirement())));
        runStep(source, request, resolved, "generate-patch", TemporalActivityPolicies.Kind.LLM,
                Map.of("plan", artifacts.get("plan").digest()));
        runStep(source, request, resolved, "apply-patch", TemporalActivityPolicies.Kind.SANDBOX,
                Map.of("patch", artifacts.get("patch").digest()));
        runStep(source, request, resolved, "test", TemporalActivityPolicies.Kind.SANDBOX,
                Map.of("patch", artifacts.get("patch").digest()));
        runStep(source, request, resolved, "quality", TemporalActivityPolicies.Kind.ASSURANCE,
                Map.of("tests", artifacts.get("tests").digest()));
        runStep(source, request, resolved, "security", TemporalActivityPolicies.Kind.ASSURANCE,
                Map.of("quality", artifacts.get("quality").digest()));
        runStep(source, request, resolved, "review", TemporalActivityPolicies.Kind.LLM, Map.of(
                "plan", artifacts.get("plan").digest(), "patch", artifacts.get("patch").digest(),
                "tests", artifacts.get("tests").digest(), "quality", artifacts.get("quality").digest(),
                "security", artifacts.get("security").digest()));
        pipeline(source, "scm", TemporalActivityPolicies.Kind.SCM).prepareDelivery(
                new PipelineExecutionActivities.DeliveryRequest(request.taskId(), request.attemptId(),
                        resolved.sourceCommit()));
        phase = "WAITING_APPROVAL";
        SoftwareFactoryWorkflow.Result coordinated = delegate.run(request.withResolvedSource(resolved.sourceCommit()));
        List<String> chronology = new java.util.ArrayList<>();
        chronology.add("SOURCE_RESOLVED:" + resolved.sourceCommit());
        artifacts.keySet().forEach(name -> chronology.add("STEP_COMPLETED:" + name));
        chronology.addAll(coordinated.chronology());
        return new SoftwareFactoryWorkflow.Result(coordinated.taskId(), coordinated.attemptId(),
                coordinated.sourceCommit(), phase, chronology, coordinated.delegations(),
                coordinated.humanDecisions(), coordinated.approvedManifestId(), coordinated.approvedBy(),
                coordinated.cancellationReason(), coordinated.independentReview());
    }

    private void runStep(SoftwareFactoryWorkflow.SourceLocation source, SoftwareFactoryWorkflow.Request request,
                         SourceResolutionActivities.Result resolved, String step,
                         TemporalActivityPolicies.Kind kind, Map<String, String> inputDigests) {
        phase = "RUNNING_" + step.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        var command = new com.example.aifactory.service.PipelineStepContracts.Command(
                com.example.aifactory.service.PipelineStepContracts.SCHEMA_VERSION, step, request.taskId(),
                request.attemptId(), TemporalIds.workflow(request.taskId(), request.attemptId()),
                request.repositoryId(), resolved.sourceCommit(), inputDigests);
        var result = pipeline(source, workerKind(step), kind).execute(
                new PipelineExecutionActivities.StepRequest(command, resolved.workspace()));
        artifacts.putAll(result.artifacts());
    }

    private static String workerKind(String step) {
        return switch (step) {
            case "plan", "generate-patch", "review" -> "llm";
            case "apply-patch", "test" -> "sandbox";
            case "quality", "security" -> "assurance";
            default -> throw new IllegalArgumentException("Unsupported pipeline step");
        };
    }

    private static PipelineExecutionActivities pipeline(SoftwareFactoryWorkflow.SourceLocation source,
                                                        String workerKind, TemporalActivityPolicies.Kind kind) {
        String queue = source.taskQueues().get(workerKind);
        return io.temporal.workflow.Workflow.newActivityStub(PipelineExecutionActivities.class,
                TemporalActivityPolicies.forKind(kind, queue));
    }

    @Override public void approve(SoftwareFactoryWorkflow.ApprovalSignal signal) { delegate.approve(signal); }
    @Override public void cancel(SoftwareFactoryWorkflow.CancellationSignal signal) { delegate.cancel(signal); }
    @Override public void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal) { delegate.decide(signal); }
    @Override public String status() { return "CREATED".equals(phase) ? delegate.status() : phase; }
    @Override public List<SoftwareFactoryWorkflow.DelegationView> dag() { return delegate.dag(); }
    @Override public Map<String, DelegationWorkflow.Budget> budgets() { return delegate.budgets(); }
    @Override public List<String> evidence() {
        return java.util.stream.Stream.concat(artifacts.values().stream().map(
                com.example.aifactory.service.PipelineStepContracts.ArtifactReference::uri),
                delegate.evidence().stream()).distinct().sorted().toList();
    }
    @Override public List<SoftwareFactoryWorkflow.PendingEffectView> pendingEffects() {
        return delegate.pendingEffects();
    }
}
