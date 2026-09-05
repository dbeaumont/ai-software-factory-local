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
    private String currentStep = "source";
    private SoftwareFactoryWorkflow.CancellationSignal cancellation;
    private SoftwareFactoryWorkflow.ApprovalSignal approval;
    private final Map<String, SoftwareFactoryWorkflow.HumanDecisionSignal> humanDecisions = new LinkedHashMap<>();
    private final Map<String, com.example.aifactory.service.PipelineStepContracts.ArtifactReference> artifacts =
            new LinkedHashMap<>();

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public SoftwareFactoryWorkflow.Result run(SoftwareFactoryWorkflow.Request request) {
        requireProductionExecutionMode(request);
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
        try {
            throwIfCancelled();
            runStep(source, request, resolved, "plan", TemporalActivityPolicies.Kind.LLM,
                    Map.of("requirement", TemporalIds.sha256(request.requirement())));
            generateAndRepairPatch(source, request, resolved);
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
            currentStep = "prepare-delivery";
            pipeline(source, "scm", TemporalActivityPolicies.Kind.SCM).prepareDelivery(
                    new PipelineExecutionActivities.DeliveryRequest(request.taskId(), request.attemptId(),
                            resolved.sourceCommit()));
            throwIfCancelled();
        } catch (RuntimeException failure) {
            if (failure instanceof RequestedCancellation) {
                return cancelBeforeApproval(source, request, resolved);
            }
            if (!isBusinessGateFailure(failure)) throw failure;
            phase = "GATE_REJECTED:" + currentStep;
            pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE).recordGateRejection(
                    new PipelineExecutionActivities.GateRejection(request.taskId(), request.attemptId(),
                            resolved.sourceCommit(), currentStep));
            List<String> chronology = new java.util.ArrayList<>();
            chronology.add("SOURCE_RESOLVED:" + resolved.sourceCommit());
            artifacts.keySet().forEach(name -> chronology.add("EVIDENCE_PRESERVED:" + name));
            chronology.add("GATE_REJECTED:" + currentStep);
            return new SoftwareFactoryWorkflow.Result(request.taskId(), request.attemptId(),
                    resolved.sourceCommit(), phase, chronology, List.of(), Map.of(),
                    null, null, null, null);
        }
        var storedManifest = pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE)
                .createApprovalManifest(new PipelineExecutionActivities.ApprovalManifestRequest(
                        request.taskId(), request.attemptId(), request.repositoryId(), resolved.sourceCommit(), artifacts));
        SoftwareFactoryWorkflow.ApprovalRequest approvalRequest = new SoftwareFactoryWorkflow.ApprovalRequest(
                storedManifest.manifestId(), storedManifest.uri(), storedManifest.digest());
        phase = "WAITING_APPROVAL";
        if (request.executionMode() == SoftwareFactoryWorkflow.WorkflowExecutionMode.HIERARCHICAL_ACTIVE
                && request.independentReview() != null) {
            request.independentReview().bundle().requireProductionArtifactBinding(artifacts);
        }
        SoftwareFactoryWorkflow.Result coordinated = delegate.run(
                request.withResolvedSource(resolved.sourceCommit()).withApprovalRequest(approvalRequest));
        phase = coordinated.status();
        List<String> chronology = new java.util.ArrayList<>();
        chronology.add("SOURCE_RESOLVED:" + resolved.sourceCommit());
        artifacts.keySet().forEach(name -> chronology.add("STEP_COMPLETED:" + name));
        chronology.addAll(coordinated.chronology());
        coordinated.humanDecisions().keySet().stream().sorted().forEach(requestId -> {
            SoftwareFactoryWorkflow.HumanDecisionSignal decision = humanDecisions.get(requestId);
            pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE).recordHumanDecision(
                    new PipelineExecutionActivities.HumanDecision(request.taskId(), request.attemptId(),
                            resolved.sourceCommit(), requestId, decision.decision(), decision.objectDigest(),
                            decision.actor(), decision.actorRole(), decision.decidedAt()));
        });
        if ("APPROVED".equals(coordinated.status())) {
            pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE).recordApproval(
                    new PipelineExecutionActivities.Approval(request.taskId(), request.attemptId(),
                            resolved.sourceCommit(), approval.manifestId(), approval.manifestDigest(),
                            approval.approver(), approval.decidedAt()));
            currentStep = "delivery";
            pipeline(source, "scm", TemporalActivityPolicies.Kind.SCM).deliver(
                    new PipelineExecutionActivities.DeliveryRequest(request.taskId(), request.attemptId(),
                            resolved.sourceCommit()));
            phase = "PR_CREATED";
            chronology.add("DELIVERY_COMPLETED");
        }
        return new SoftwareFactoryWorkflow.Result(coordinated.taskId(), coordinated.attemptId(),
                coordinated.sourceCommit(), phase, chronology, coordinated.delegations(),
                coordinated.humanDecisions(), coordinated.approvedManifestId(), coordinated.approvedBy(),
                coordinated.cancellationReason(), coordinated.independentReview());
    }

    private SoftwareFactoryWorkflow.Result cancelBeforeApproval(SoftwareFactoryWorkflow.SourceLocation source,
                                                                 SoftwareFactoryWorkflow.Request request,
                                                                 SourceResolutionActivities.Result resolved) {
        phase = "CANCELLED";
        pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE).recordCancellation(
                new PipelineExecutionActivities.Cancellation(request.taskId(), request.attemptId(),
                        resolved.sourceCommit(), cancellation.reason(), cancellation.actor()));
        List<String> chronology = new java.util.ArrayList<>();
        chronology.add("SOURCE_RESOLVED:" + resolved.sourceCommit());
        artifacts.keySet().forEach(name -> chronology.add("EVIDENCE_PRESERVED:" + name));
        chronology.add("CANCELLED");
        return new SoftwareFactoryWorkflow.Result(request.taskId(), request.attemptId(), resolved.sourceCommit(),
                "CANCELLED", chronology, List.of(), Map.of(), null, null, cancellation.reason(), null);
    }

    private void throwIfCancelled() {
        if (cancellation != null && cancellation.reason() != null && !cancellation.reason().isBlank()
                && cancellation.actor() != null && !cancellation.actor().isBlank()) {
            throw new RequestedCancellation();
        }
    }

    static boolean isBusinessGateFailure(Throwable failure) {
        return TemporalFailureClassifier.classify(failure).type()
                == TemporalFailureClassifier.Type.BUSINESS_REJECTION;
    }

    static void requireProductionExecutionMode(SoftwareFactoryWorkflow.Request request) {
        if (request == null) throw new IllegalArgumentException("Production workflow request is required");
        if (request.executionMode() == SoftwareFactoryWorkflow.WorkflowExecutionMode.PIPELINE
                && (!request.delegations().isEmpty() || request.independentReview() != null)) {
            throw new IllegalArgumentException("PIPELINE mode cannot start hierarchical child workflows");
        }
    }

    private void generateAndRepairPatch(SoftwareFactoryWorkflow.SourceLocation source,
                                        SoftwareFactoryWorkflow.Request request,
                                        SourceResolutionActivities.Result resolved) {
        currentStep = "generate-patch";
        PipelineExecutionActivities llm = pipeline(source, "llm", TemporalActivityPolicies.Kind.LLM);
        PipelineExecutionActivities sandbox = pipeline(source, "sandbox", TemporalActivityPolicies.Kind.SANDBOX);
        var generated = command(request, resolved, "generate-patch-candidate",
                Map.of("plan", artifacts.get("plan").digest()));
        var candidate = llm.generatePatchCandidate(
                new PipelineExecutionActivities.StepRequest(generated, resolved.workspace()));
        artifacts.putAll(candidate.artifacts());
        for (int repairAttempt = 0; repairAttempt <= 2; repairAttempt++) {
            var validationCommand = command(request, resolved, "validate-patch-candidate", Map.of(
                    "candidate", artifacts.get("patch-candidate").digest(),
                    "attempt", TemporalIds.sha256(Integer.toString(repairAttempt))));
            var validation = sandbox.validatePatchCandidate(
                    new PipelineExecutionActivities.StepRequest(validationCommand, resolved.workspace()));
            artifacts.putAll(validation.result().artifacts());
            throwIfCancelled();
            if (validation.valid()) return;
            if (repairAttempt == 2) {
                throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                        "Patch remains invalid after two workflow repair attempts", "BUSINESS_REJECTION");
            }
            var repairCommand = command(request, resolved, "repair-patch-candidate", Map.of(
                    "candidate", artifacts.get("patch-candidate").digest(),
                    "validation-error", validation.validationError().digest()));
            var repaired = llm.repairPatchCandidate(new PipelineExecutionActivities.PatchRepairRequest(
                    repairCommand, resolved.workspace(), validation.validationError(), repairAttempt + 1));
            artifacts.putAll(repaired.artifacts());
            throwIfCancelled();
        }
    }

    private static com.example.aifactory.service.PipelineStepContracts.Command command(
            SoftwareFactoryWorkflow.Request request, SourceResolutionActivities.Result resolved,
            String step, Map<String, String> inputDigests) {
        return new com.example.aifactory.service.PipelineStepContracts.Command(
                com.example.aifactory.service.PipelineStepContracts.SCHEMA_VERSION, step, request.taskId(),
                request.attemptId(), TemporalIds.workflow(request.taskId(), request.attemptId()),
                request.repositoryId(), resolved.sourceCommit(), inputDigests);
    }

    private void runStep(SoftwareFactoryWorkflow.SourceLocation source, SoftwareFactoryWorkflow.Request request,
                         SourceResolutionActivities.Result resolved, String step,
                         TemporalActivityPolicies.Kind kind, Map<String, String> inputDigests) {
        phase = "RUNNING_" + step.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        currentStep = step;
        var command = command(request, resolved, step, inputDigests);
        var result = pipeline(source, workerKind(step), kind).execute(
                new PipelineExecutionActivities.StepRequest(command, resolved.workspace()));
        artifacts.putAll(result.artifacts());
        throwIfCancelled();
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

    @Override public void approve(SoftwareFactoryWorkflow.ApprovalSignal signal) {
        approval = signal;
        delegate.approve(signal);
    }
    @Override public void cancel(SoftwareFactoryWorkflow.CancellationSignal signal) {
        cancellation = signal;
        delegate.cancel(signal);
    }
    @Override public void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal) {
        if (signal != null && signal.decisionId() != null) humanDecisions.put(signal.decisionId(), signal);
        delegate.decide(signal);
    }
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

    private static final class RequestedCancellation extends RuntimeException {}
}
