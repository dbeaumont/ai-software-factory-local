package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aEnvelopeFactory;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aExtensions;
import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** V1 admission wrapper around the already replay-tested durable coordination implementation. */
public final class SoftwareFactoryExecutionWorkflowV1Impl extends ProductionExecutionWorkflowRuntime
        implements SoftwareFactoryExecutionWorkflowV1 {
    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public SoftwareFactoryWorkflow.Result run(SoftwareFactoryWorkflow.Request request) {
        return execute(request, null, false, null, null);
    }
}

/** Shared durable execution mechanics; versioned workflow boundaries own admission and routing. */
abstract class ProductionExecutionWorkflowRuntime {
    private final SoftwareFactoryWorkflow delegate = new SoftwareFactoryWorkflowImpl();
    private String phase = "CREATED";
    private String currentStep = "source";
    private SoftwareFactoryWorkflow.CancellationSignal cancellation;
    private SoftwareFactoryWorkflow.ApprovalSignal approval;
    private final Map<String, SoftwareFactoryWorkflow.HumanDecisionSignal> humanDecisions = new LinkedHashMap<>();
    private final Map<String, com.example.aifactory.service.PipelineStepContracts.ArtifactReference> artifacts =
            new LinkedHashMap<>();
    private final A2aTaskAwaiter a2aTasks = new A2aTaskAwaiter();
    private final A2aActivities.Stubs a2a = A2aActivities.newStubs();
    private final List<DelegationWorkflow.Result> pipelineDelegations = new java.util.ArrayList<>();
    private final Map<String, A2aActivities.EvidenceReference> hierarchicalResults = new LinkedHashMap<>();
    private final List<HierarchicalExecutionActivities.ReviewedSpecialistResult> reviewedSpecialistResults =
            new java.util.ArrayList<>();
    private String architectureAssessmentId;
    private String hierarchicalPlanId;
    private SoftwareFactoryWorkflow.Request activeRequest;
    private SoftwareFactoryWorkflow.ApprovalRequest activeApprovalRequest;

    protected final SoftwareFactoryWorkflow.Result execute(
            SoftwareFactoryWorkflow.Request request, SourceResolutionActivities.Result admittedSource,
            boolean sourceAlreadyBound, String selectedPath, String routingDecisionId) {
        requireProductionExecutionMode(request);
        SoftwareFactoryWorkflow.SourceLocation source = request == null ? null : request.sourceLocation();
        if (source == null) throw new IllegalArgumentException("Production workflow source location is required");
        SourceResolutionActivities.Result resolved = admittedSource == null
                ? resolveSource(request, source) : admittedSource;
        if (!request.repositoryId().equals(resolved.repositoryId()) || !source.branch().equals(resolved.branch())) {
            throw new SecurityException("Resolved source attestation changed workflow identity");
        }
        phase = "SOURCE_RESOLVED";
        if (!sourceAlreadyBound) bindSource(source, request, resolved);
        com.example.aifactory.workflow.EvidenceRepository.StoredManifest storedManifest = null;
        try {
            throwIfCancelled();
            if ("HIERARCHICAL_PATH".equals(selectedPath)) {
                runArchitectureAndCode(source, request, resolved, routingDecisionId);
            } else if ("SHORT_CODE_PATH".equals(selectedPath)) {
                runShortPlan(source, request, resolved, routingDecisionId);
            } else {
                runPipelineAgent(source, request, resolved, "architecture-agent", "PLAN", "plan",
                        Map.of("requirement", request.requirementDigest()), null, 0);
            }
            generateAndRepairPatch(source, request, resolved, selectedPath, routingDecisionId);
            runStep(source, request, resolved, "apply-patch", TemporalActivityPolicies.Kind.SANDBOX,
                    Map.of("patch", artifacts.get("patch").digest()));
            if ("SHORT_CODE_PATH".equals(selectedPath)) {
                runStep(source, request, resolved, "test", TemporalActivityPolicies.Kind.SANDBOX,
                        Map.of("patch", artifacts.get("patch").digest()));
            } else if ("HIERARCHICAL_PATH".equals(selectedPath)) {
                var strategy = runTestDesign(source, request, resolved, routingDecisionId);
                runStep(source, request, resolved, "test", TemporalActivityPolicies.Kind.SANDBOX,
                        Map.of("patch", artifacts.get("patch").digest()));
                runTestAssessment(source, request, resolved, routingDecisionId, strategy);
            } else {
                runPipelineAgent(source, request, resolved, "test-agent", "ASSESS_TESTS", "test",
                        Map.of("patch", artifacts.get("patch").digest()), null, 0);
            }
            runStep(source, request, resolved, "quality", TemporalActivityPolicies.Kind.ASSURANCE,
                    Map.of("tests", artifacts.get("tests").digest()));
            runStep(source, request, resolved, "security", TemporalActivityPolicies.Kind.ASSURANCE,
                    Map.of("quality", artifacts.get("quality").digest()));
            if (selectedPath != null) {
                if ("HIERARCHICAL_PATH".equals(selectedPath)) {
                    runSecuritySpecialist(source, request, resolved, routingDecisionId);
                }
                currentStep = "prepare-delivery";
                pipeline(source, "scm", TemporalActivityPolicies.Kind.SCM).prepareDelivery(
                        new PipelineExecutionActivities.DeliveryRequest(request.taskId(), request.attemptId(),
                                resolved.sourceCommit()));
                var preparedReview = prepareIndependentReview(source, request, resolved, selectedPath);
                storedManifest = preparedReview.manifest();
                runIndependentReview(request, resolved, preparedReview.bundle());
            } else {
                runPipelineAgent(source, request, resolved, "independent-reviewer", "REVIEW", "review", Map.of(
                        "plan", artifacts.get("plan").digest(), "patch", artifacts.get("patch").digest(),
                        "tests", artifacts.get("tests").digest(), "quality", artifacts.get("quality").digest(),
                        "security", artifacts.get("security").digest()), null, 0);
                currentStep = "prepare-delivery";
                pipeline(source, "scm", TemporalActivityPolicies.Kind.SCM).prepareDelivery(
                        new PipelineExecutionActivities.DeliveryRequest(request.taskId(), request.attemptId(),
                                resolved.sourceCommit()));
            }
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
                    resolved.sourceCommit(), phase, chronology, pipelineDelegations, Map.of(),
                    null, null, null, null);
        }
        if (storedManifest == null) {
            storedManifest = pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE)
                    .createApprovalManifest(new PipelineExecutionActivities.ApprovalManifestRequest(
                            request.taskId(), request.attemptId(), request.repositoryId(),
                            resolved.sourceCommit(), artifacts));
        }
        SoftwareFactoryWorkflow.ApprovalRequest approvalRequest = new SoftwareFactoryWorkflow.ApprovalRequest(
                storedManifest.manifestId(), storedManifest.uri(), storedManifest.digest());
        activeRequest = request;
        activeApprovalRequest = approvalRequest;
        phase = "WAITING_APPROVAL";
        if (request.executionMode() == SoftwareFactoryWorkflow.WorkflowExecutionMode.HIERARCHICAL_ACTIVE
                && request.independentReview() != null) {
            request.independentReview().bundle().requireProductionArtifactBinding(artifacts);
        }
        SoftwareFactoryWorkflow.Request approvalBoundRequest = request.withResolvedSource(resolved.sourceCommit())
                .withApprovalRequest(approvalRequest);
        SoftwareFactoryWorkflow.Result coordinated = selectedPath == null
                ? delegate.run(approvalBoundRequest)
                : awaitNativeHierarchicalApproval(approvalBoundRequest);
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
        if ("CANCELLED".equals(coordinated.status())) {
            pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE).recordCancellation(
                    new PipelineExecutionActivities.Cancellation(request.taskId(), request.attemptId(),
                            resolved.sourceCommit(), coordinated.cancellationReasonDigest(), cancellation.actor()));
        }
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
                coordinated.sourceCommit(), phase, chronology,
                java.util.stream.Stream.concat(pipelineDelegations.stream(), coordinated.delegations().stream()).toList(),
                coordinated.humanDecisions(), coordinated.approvedManifestId(), coordinated.approvedBy(),
                coordinated.cancellationReasonDigest(), coordinated.independentReview());
    }

    private static SourceResolutionActivities.Result resolveSource(
            SoftwareFactoryWorkflow.Request request, SoftwareFactoryWorkflow.SourceLocation source) {
        SourceResolutionActivities activities = io.temporal.workflow.Workflow.newActivityStub(
                SourceResolutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.READ, source.contextTaskQueue()));
        String inputDigest = TemporalIds.sha256(String.join("\u0000", request.repositoryId(),
                source.repositoryUrl(), source.branch()));
        String idempotencyKey = TemporalIds.effectKey(request.taskId(), request.attemptId(), "source",
                "resolve", 1, inputDigest, inputDigest);
        return activities.resolve(new SourceResolutionActivities.Request(
                request.taskId(), request.attemptId(), request.repositoryId(), source.repositoryUrl(),
                source.branch(), idempotencyKey));
    }

    protected final void bindSource(SoftwareFactoryWorkflow.SourceLocation source,
                                    SoftwareFactoryWorkflow.Request request,
                                    SourceResolutionActivities.Result resolved) {
        pipeline(source, "context", TemporalActivityPolicies.Kind.READ).bindSource(
                new PipelineExecutionActivities.SourceBinding(request.taskId(), request.attemptId(),
                        request.repositoryId(), resolved.sourceCommit(), resolved.workspace(),
                        resolved.attestationDigest()));
    }

    protected final void recordRoutingTriage(SoftwareFactoryWorkflow.SourceLocation source,
                                              SoftwareFactoryWorkflow.Request request,
                                              SourceResolutionActivities.Result resolved) {
        pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE).recordGateRejection(
                new PipelineExecutionActivities.GateRejection(request.taskId(), request.attemptId(),
                        resolved.sourceCommit(), "routing"));
    }

    private SoftwareFactoryWorkflow.Result cancelBeforeApproval(SoftwareFactoryWorkflow.SourceLocation source,
                                                                 SoftwareFactoryWorkflow.Request request,
                                                                 SourceResolutionActivities.Result resolved) {
        phase = "CANCELLED";
        pipeline(source, "evidence", TemporalActivityPolicies.Kind.EVIDENCE).recordCancellation(
                new PipelineExecutionActivities.Cancellation(request.taskId(), request.attemptId(),
                        resolved.sourceCommit(), cancellation.reasonDigest(), cancellation.actor()));
        List<String> chronology = new java.util.ArrayList<>();
        chronology.add("SOURCE_RESOLVED:" + resolved.sourceCommit());
        artifacts.keySet().forEach(name -> chronology.add("EVIDENCE_PRESERVED:" + name));
        chronology.add("CANCELLED");
        return new SoftwareFactoryWorkflow.Result(request.taskId(), request.attemptId(), resolved.sourceCommit(),
                "CANCELLED", chronology, pipelineDelegations, Map.of(), null, null,
                cancellation.reasonDigest(), null);
    }

    private void throwIfCancelled() {
        if (cancellation != null && cancellation.reasonDigest() != null
                && cancellation.reasonDigest().matches("[0-9a-f]{64}")
                && cancellation.actor() != null && !cancellation.actor().isBlank()) {
            throw new RequestedCancellation();
        }
    }

    private SoftwareFactoryWorkflow.Result awaitNativeHierarchicalApproval(
            SoftwareFactoryWorkflow.Request request) {
        List<String> chronology = new java.util.ArrayList<>();
        chronology.add("WAITING_APPROVAL:" + activeApprovalRequest.manifestId());
        io.temporal.workflow.Workflow.await(() -> cancellationMatches(request) || approvalMatches(request));
        if (cancellationMatches(request)) {
            chronology.add("CANCELLED");
            return new SoftwareFactoryWorkflow.Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                    "CANCELLED", chronology, List.of(), Map.of(), null, null,
                    cancellation.reasonDigest(), null);
        }
        if ("REJECT".equals(approval.decision())) {
            chronology.add("REJECTED:" + activeApprovalRequest.manifestId());
            return new SoftwareFactoryWorkflow.Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                    "REJECTED", chronology, List.of(), Map.of(), null, null, null, null);
        }
        chronology.add("APPROVED:" + activeApprovalRequest.manifestId());
        return new SoftwareFactoryWorkflow.Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                "APPROVED", chronology, List.of(), Map.of(), activeApprovalRequest.manifestId(),
                approval.approver(), null, null);
    }

    private boolean approvalMatches(SoftwareFactoryWorkflow.Request request) {
        return approval != null && activeApprovalRequest != null
                && request.taskId().equals(approval.taskId())
                && request.attemptId().equals(approval.attemptId())
                && activeApprovalRequest.manifestId().equals(approval.manifestId())
                && activeApprovalRequest.digest().equals(approval.manifestDigest())
                && java.util.Set.of("APPROVE", "REJECT").contains(approval.decision())
                && approval.approver() != null && !approval.approver().isBlank()
                && approval.decidedAt() != null && !approval.decidedAt().isBlank();
    }

    private boolean cancellationMatches(SoftwareFactoryWorkflow.Request request) {
        return cancellation != null && request.taskId().equals(cancellation.taskId())
                && request.attemptId().equals(cancellation.attemptId())
                && cancellation.reasonDigest() != null && cancellation.reasonDigest().matches("[0-9a-f]{64}")
                && cancellation.actor() != null && !cancellation.actor().isBlank()
                && cancellation.decidedAt() != null && !cancellation.decidedAt().isBlank();
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
                                        SourceResolutionActivities.Result resolved,
                                        String selectedPath, String routingDecisionId) {
        currentStep = "generate-patch";
        PipelineExecutionActivities sandbox = pipeline(source, "sandbox", TemporalActivityPolicies.Kind.SANDBOX);
        if (selectedPath != null) {
            generateHierarchicalPatch(source, request, resolved, selectedPath, routingDecisionId);
        } else {
            runPipelineAgent(source, request, resolved, "developer", "GENERATE_PATCH", "generate-patch-candidate",
                    Map.of("plan", artifacts.get("plan").digest()), null, 0);
        }
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
            if (selectedPath != null) {
                runHierarchicalPatchRepair(source, request, resolved, selectedPath,
                        validation.validationError(), repairAttempt + 1);
            } else {
                runPipelineAgent(source, request, resolved, "patch-repair", "REPAIR_PATCH",
                        "repair-patch-candidate", Map.of(
                                "candidate", artifacts.get("patch-candidate").digest(),
                                "validation-error", validation.validationError().digest()),
                        validation.validationError(), repairAttempt + 1);
            }
            throwIfCancelled();
        }
    }

    private void runHierarchicalPatchRepair(SoftwareFactoryWorkflow.SourceLocation source,
                                            SoftwareFactoryWorkflow.Request request,
                                            SourceResolutionActivities.Result resolved,
                                            String selectedPath,
                                            com.example.aifactory.service.PipelineStepContracts.ArtifactReference error,
                                            int repairAttempt) {
        currentStep = "repair-patch-candidate";
        HierarchicalExecutionActivities hierarchical = io.temporal.workflow.Workflow.newActivityStub(
                HierarchicalExecutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.EVIDENCE, source.taskQueues().get("evidence")));
        DelegationWorkflow.Budget budget = new DelegationWorkflow.Budget(6_000, 6_000_000, 3, 360);
        var task = hierarchical.preparePatchRepair(new HierarchicalExecutionActivities.PreparePatchRepair(
                request.taskId(), request.attemptId(), resolved.sourceCommit(), hierarchicalPlanId,
                repairAttempt, artifacts.get("patch-candidate"), error, budget));
        DelegationWorkflow child = io.temporal.workflow.Workflow.newChildWorkflowStub(
                DelegationWorkflow.class, io.temporal.workflow.ChildWorkflowOptions.newBuilder()
                        .setWorkflowId(TemporalIds.delegation(
                                request.taskId(), request.attemptId(), task.nodeId()))
                        .build());
        DelegationWorkflow.Result result = child.run(new DelegationWorkflow.Request(
                request.taskId(), request.attemptId(), task.nodeId(),
                "SHORT_CODE_PATH".equals(selectedPath) ? "short-plan" : "code", "patch-repair",
                resolved.sourceCommit(), request.requirementDigest(), 100,
                java.util.Set.of(), task.budget(), task.inputReference()));
        pipelineDelegations.add(result);
        if (!"READY_FOR_ACTIVITIES".equals(result.status()) || result.artifacts().size() != 1) {
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Patch Repair did not produce one usable proposal", "BUSINESS_REJECTION");
        }
        var accepted = hierarchical.acceptPatchRepair(new HierarchicalExecutionActivities.AcceptPatchRepair(
                request.taskId(), request.attemptId(), resolved.sourceCommit(), task,
                result.artifacts().getFirst()));
        artifacts.put("patch-candidate", accepted.patchCandidate());
        reviewedSpecialistResults.add(accepted.reviewedResult());
    }

    private void runArchitectureAndCode(SoftwareFactoryWorkflow.SourceLocation source,
                                        SoftwareFactoryWorkflow.Request request,
                                        SourceResolutionActivities.Result resolved,
                                        String routingDecisionId) {
        if (routingDecisionId == null || !routingDecisionId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Hierarchical execution requires its routing decision ID");
        }
        hierarchicalPlanId = routingDecisionId;
        var architecture = runHierarchicalSpecialist(source, request, resolved, routingDecisionId,
                "architecture", "supervisor", "supervisor", "architecture-agent", List.of(), java.util.Set.of(),
                java.util.Set.of("context.list_tree", "context.search_code", "context.read_file",
                        "context.get_repository_rules", "context.get_dependencies", "context.get_symbols"),
                List.of("Return a source-bound architecture assessment"));
        var acceptedArchitecture = acceptHierarchicalSpecialist(source, request, resolved,
                "architecture-agent", "architecture-assessment-v1", architecture,
                java.util.Set.of("specialist-architecture"), false);
        artifacts.put("architecture", acceptedArchitecture.artifact());
        architectureAssessmentId = acceptedArchitecture.documentId();
        reviewedSpecialistResults.add(reviewed("architecture-agent", acceptedArchitecture));
        hierarchicalResults.put("architecture", architecture);

        var code = runHierarchicalSpecialist(source, request, resolved, routingDecisionId,
                "code", "supervisor", "supervisor", "code-agent",
                List.of(new HierarchicalExecutionActivities.InputEvidence(
                        "SPECIALIST_RESULT", architecture.uri(), architecture.digest())), java.util.Set.of("architecture"),
                java.util.Set.of("context.list_tree", "context.search_code", "context.get_repository_rules"),
                List.of("Return bounded Developer tasks and their safe integration order"));
        var acceptedCode = acceptHierarchicalSpecialist(source, request, resolved,
                "code-agent", "integration-proposal-v1", code,
                java.util.Set.of(routingDecisionId, "code", acceptedArchitecture.documentId()), true);
        artifacts.put("integration-plan", acceptedCode.artifact());
        artifacts.put("plan", acceptedCode.artifact());
        reviewedSpecialistResults.add(reviewed("code-agent", acceptedCode));
        hierarchicalResults.put("code", code);
    }

    private void runShortPlan(SoftwareFactoryWorkflow.SourceLocation source,
                              SoftwareFactoryWorkflow.Request request,
                              SourceResolutionActivities.Result resolved,
                              String routingDecisionId) {
        if (routingDecisionId == null || !routingDecisionId.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")) {
            throw new IllegalArgumentException("Short hierarchical execution requires its routing decision ID");
        }
        var plan = runHierarchicalSpecialist(source, request, resolved, routingDecisionId,
                "short-plan", "supervisor", "supervisor", "supervisor", List.of(), java.util.Set.of(),
                java.util.Set.of(),
                List.of("Return exactly one bounded Developer node for the short path"));
        var accepted = acceptHierarchicalSpecialist(source, request, resolved,
                "supervisor", "delegation-plan-v1", plan,
                java.util.Set.of("specialist-short-plan"), true);
        hierarchicalPlanId = accepted.documentId();
        artifacts.put("plan", accepted.artifact());
        reviewedSpecialistResults.add(reviewed("supervisor", accepted));
        hierarchicalResults.put("short-plan", plan);
    }

    private void generateHierarchicalPatch(SoftwareFactoryWorkflow.SourceLocation source,
                                           SoftwareFactoryWorkflow.Request request,
                                           SourceResolutionActivities.Result resolved,
                                           String selectedPath,
                                           String routingDecisionId) {
        currentStep = "developer-tasks";
        DelegationWorkflow.Budget budget = new DelegationWorkflow.Budget(12_000, 12_000_000, 6, 900);
        HierarchicalExecutionActivities hierarchical = io.temporal.workflow.Workflow.newActivityStub(
                HierarchicalExecutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.EVIDENCE, source.taskQueues().get("evidence")));
        List<HierarchicalExecutionActivities.DeveloperTask> tasks;
        if ("SHORT_CODE_PATH".equals(selectedPath)) {
            tasks = hierarchical.prepareShortDeveloperTasks(
                    new HierarchicalExecutionActivities.PrepareShortDeveloperTasks(
                            request.taskId(), request.attemptId(), request.repositoryId(), resolved.sourceCommit(),
                            hierarchicalPlanId, hierarchicalResults.get("short-plan"), budget));
        } else {
            tasks = hierarchical.prepareDeveloperTasks(
                    new HierarchicalExecutionActivities.PrepareDeveloperTasks(
                            request.taskId(), request.attemptId(), request.repositoryId(), resolved.sourceCommit(),
                            routingDecisionId, architectureAssessmentId, hierarchicalResults.get("architecture"),
                            hierarchicalResults.get("code"), budget));
        }
        List<HierarchicalExecutionActivities.DeveloperPatchResult> patches = new java.util.ArrayList<>();
        for (HierarchicalExecutionActivities.DeveloperTask task : tasks) {
            currentStep = task.nodeId();
            DelegationWorkflow child = io.temporal.workflow.Workflow.newChildWorkflowStub(
                    DelegationWorkflow.class, io.temporal.workflow.ChildWorkflowOptions.newBuilder()
                            .setWorkflowId(TemporalIds.delegation(
                                    request.taskId(), request.attemptId(), task.nodeId()))
                            .build());
            DelegationWorkflow.Result result = child.run(new DelegationWorkflow.Request(
                    request.taskId(), request.attemptId(), task.nodeId(),
                    "SHORT_CODE_PATH".equals(selectedPath) ? "short-plan" : "code", "developer",
                    resolved.sourceCommit(), request.requirementDigest(), 100,
                    task.dependsOn(), task.budget(), task.inputReference()));
            pipelineDelegations.add(result);
            if (!"READY_FOR_ACTIVITIES".equals(result.status()) || result.artifacts().size() != 1) {
                throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                        "Developer did not produce one usable patch proposal", "BUSINESS_REJECTION");
            }
            patches.add(new HierarchicalExecutionActivities.DeveloperPatchResult(
                    task, result.artifacts().getFirst()));
        }
        var accepted = hierarchical.acceptDeveloperPatches(
                new HierarchicalExecutionActivities.AcceptDeveloperPatches(
                        request.taskId(), request.attemptId(), resolved.sourceCommit(), patches));
        artifacts.put("patch-candidate", accepted.patchCandidate());
        reviewedSpecialistResults.addAll(accepted.reviewedResults());
    }

    private HierarchicalExecutionActivities.AcceptedSpecialistResult runTestDesign(
            SoftwareFactoryWorkflow.SourceLocation source, SoftwareFactoryWorkflow.Request request,
            SourceResolutionActivities.Result resolved, String routingDecisionId) {
        var strategy = runHierarchicalSpecialist(source, request, resolved, routingDecisionId,
                "test-design", "tests", "test-agent", "test-design", List.of(
                        new HierarchicalExecutionActivities.InputEvidence("SPECIALIST_RESULT",
                                hierarchicalResults.get("code").uri(), hierarchicalResults.get("code").digest()),
                        new HierarchicalExecutionActivities.InputEvidence(
                                "EVIDENCE", artifacts.get("patch").uri(), artifacts.get("patch").digest())),
                java.util.Set.of("code"),
                java.util.Set.of("context.search_code", "context.read_file", "context.get_dependencies"),
                List.of("Return a source-bound test strategy covering the integrated patch"));
        var accepted = acceptHierarchicalSpecialist(source, request, resolved,
                "test-design", "test-strategy-v1", strategy, java.util.Set.of(), false);
        artifacts.put("test-strategy", accepted.artifact());
        reviewedSpecialistResults.add(reviewed("test-design", accepted));
        hierarchicalResults.put("test-design", strategy);
        return accepted;
    }

    private void runTestAssessment(SoftwareFactoryWorkflow.SourceLocation source,
                                   SoftwareFactoryWorkflow.Request request,
                                   SourceResolutionActivities.Result resolved,
                                   String routingDecisionId,
                                   HierarchicalExecutionActivities.AcceptedSpecialistResult strategy) {
        var deterministic = artifacts.get("tests");
        var assessment = runHierarchicalSpecialist(source, request, resolved, routingDecisionId,
                "tests", "supervisor", "supervisor", "test-agent", List.of(
                        new HierarchicalExecutionActivities.InputEvidence("SPECIALIST_RESULT",
                                hierarchicalResults.get("test-design").uri(),
                                hierarchicalResults.get("test-design").digest()),
                        new HierarchicalExecutionActivities.InputEvidence(
                                "EVIDENCE", deterministic.uri(), deterministic.digest())),
                java.util.Set.of("code", "test-design"),
                java.util.Set.of("context.search_code", "context.read_file", "evidence.get_summary"),
                List.of("Assess deterministic test evidence against the accepted test strategy"));
        var accepted = acceptHierarchicalSpecialist(source, request, resolved,
                "test-agent", "test-assessment-v1", assessment,
                java.util.Set.of(strategy.documentId()), false);
        artifacts.put("test-assessment", accepted.artifact());
        reviewedSpecialistResults.add(reviewed("test-agent", accepted));
        hierarchicalResults.put("tests", assessment);
    }

    private void runSecuritySpecialist(SoftwareFactoryWorkflow.SourceLocation source,
                                       SoftwareFactoryWorkflow.Request request,
                                       SourceResolutionActivities.Result resolved,
                                       String routingDecisionId) {
        var deterministic = artifacts.get("security");
        var security = runHierarchicalSpecialist(source, request, resolved, routingDecisionId,
                "security-agent", "supervisor", "supervisor", "security-agent", List.of(
                        new HierarchicalExecutionActivities.InputEvidence("SPECIALIST_RESULT",
                                hierarchicalResults.get("architecture").uri(),
                                hierarchicalResults.get("architecture").digest()),
                        new HierarchicalExecutionActivities.InputEvidence("SPECIALIST_RESULT",
                                hierarchicalResults.get("code").uri(), hierarchicalResults.get("code").digest()),
                        new HierarchicalExecutionActivities.InputEvidence(
                                "EVIDENCE", deterministic.uri(), deterministic.digest())),
                java.util.Set.of("architecture", "code"),
                java.util.Set.of("context.search_code", "context.read_file", "context.get_dependencies",
                        "context.get_symbols", "evidence.get_summary"),
                List.of("Assess the deterministic security evidence without weakening its findings"));
        var accepted = acceptHierarchicalSpecialist(source, request, resolved,
                "security-agent", "security-assessment-v1", security, java.util.Set.of(), false);
        artifacts.put("security-assessment", accepted.artifact());
        reviewedSpecialistResults.add(reviewed("security-agent", accepted));
        hierarchicalResults.put("security", security);
    }

    private HierarchicalExecutionActivities.PreparedIndependentReview prepareIndependentReview(
            SoftwareFactoryWorkflow.SourceLocation source, SoftwareFactoryWorkflow.Request request,
            SourceResolutionActivities.Result resolved, String selectedPath) {
        HierarchicalExecutionActivities hierarchical = io.temporal.workflow.Workflow.newActivityStub(
                HierarchicalExecutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.EVIDENCE, source.taskQueues().get("evidence")));
        return hierarchical.prepareIndependentReview(new HierarchicalExecutionActivities.PrepareIndependentReview(
                request.taskId(), request.attemptId(), request.repositoryId(), resolved.sourceCommit(),
                artifacts, reviewedSpecialistResults, "SHORT_CODE_PATH".equals(selectedPath)
                ? java.util.Set.of("supervisor", "developer")
                : java.util.Set.of("architecture-agent", "code-agent", "developer", "test-design",
                        "test-agent", "security-agent")));
    }

    private void runIndependentReview(SoftwareFactoryWorkflow.Request request,
                                      SourceResolutionActivities.Result resolved,
                                      com.example.aifactory.service.IndependentReviewBundle bundle) {
        currentStep = "independent-review";
        IndependentReviewWorkflow child = io.temporal.workflow.Workflow.newChildWorkflowStub(
                IndependentReviewWorkflow.class, io.temporal.workflow.ChildWorkflowOptions.newBuilder()
                        .setWorkflowId(TemporalIds.delegation(
                                request.taskId(), request.attemptId(), "independent-review"))
                        .build());
        IndependentReviewWorkflow.Result result = child.run(new IndependentReviewWorkflow.Request(
                request.taskId(), request.attemptId(), "independent-review", resolved.sourceCommit(),
                bundle, new DelegationWorkflow.Budget(10_000, 10_000_000, 6, 600)));
        pipelineDelegations.add(new DelegationWorkflow.Result(
                result.reviewId(), result.role(), result.status()));
        if (!"READY_FOR_ACTIVITIES".equals(result.status())) {
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Independent review did not accept the hierarchical result", "BUSINESS_REJECTION");
        }
    }

    private static HierarchicalExecutionActivities.ReviewedSpecialistResult reviewed(
            String role, HierarchicalExecutionActivities.AcceptedSpecialistResult result) {
        return new HierarchicalExecutionActivities.ReviewedSpecialistResult(
                result.documentId(), role, result.artifact());
    }

    private A2aActivities.EvidenceReference runHierarchicalSpecialist(
            SoftwareFactoryWorkflow.SourceLocation source, SoftwareFactoryWorkflow.Request request,
            SourceResolutionActivities.Result resolved, String routingDecisionId, String nodeId,
            String parentNodeId, String parentRole, String role,
            List<HierarchicalExecutionActivities.InputEvidence> inputs, java.util.Set<String> dependsOn,
            java.util.Set<String> allowedTools, List<String> successCriteria) {
        currentStep = nodeId;
        DelegationWorkflow.Budget budget = new DelegationWorkflow.Budget(10_000, 10_000_000, 6, 600);
        HierarchicalExecutionActivities hierarchical = io.temporal.workflow.Workflow.newActivityStub(
                HierarchicalExecutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.EVIDENCE, source.taskQueues().get("evidence")));
        A2aContracts.Part input = hierarchical.prepareSpecialistTask(
                new HierarchicalExecutionActivities.PrepareSpecialistTask(
                        request.taskId(), request.attemptId(), request.repositoryId(), resolved.sourceCommit(),
                        routingDecisionId, nodeId, parentRole, role, inputs, java.util.Set.of("."),
                        java.util.Set.of(), allowedTools, budget, successCriteria));
        DelegationWorkflow child = io.temporal.workflow.Workflow.newChildWorkflowStub(
                DelegationWorkflow.class, io.temporal.workflow.ChildWorkflowOptions.newBuilder()
                        .setWorkflowId(TemporalIds.delegation(request.taskId(), request.attemptId(), nodeId))
                        .build());
        DelegationWorkflow.Result result = child.run(new DelegationWorkflow.Request(
                request.taskId(), request.attemptId(), nodeId, parentNodeId, role,
                resolved.sourceCommit(), request.requirementDigest(), 100, dependsOn, budget, input));
        pipelineDelegations.add(result);
        if (!"READY_FOR_ACTIVITIES".equals(result.status()) || result.artifacts().size() != 1) {
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Hierarchical specialist did not produce one usable result", "BUSINESS_REJECTION");
        }
        return result.artifacts().getFirst();
    }

    private HierarchicalExecutionActivities.AcceptedSpecialistResult acceptHierarchicalSpecialist(
            SoftwareFactoryWorkflow.SourceLocation source, SoftwareFactoryWorkflow.Request request,
            SourceResolutionActivities.Result resolved, String role, String contract,
            A2aActivities.EvidenceReference reference, java.util.Set<String> allowedReferenceIds,
            boolean activateAsCodePlan) {
        HierarchicalExecutionActivities hierarchical = io.temporal.workflow.Workflow.newActivityStub(
                HierarchicalExecutionActivities.class, TemporalActivityPolicies.forKind(
                        TemporalActivityPolicies.Kind.EVIDENCE, source.taskQueues().get("evidence")));
        return hierarchical.acceptSpecialistResult(new HierarchicalExecutionActivities.AcceptSpecialistResult(
                request.taskId(), request.attemptId(), resolved.sourceCommit(), role, contract, reference,
                allowedReferenceIds, activateAsCodePlan));
    }

    private void runPipelineAgent(SoftwareFactoryWorkflow.SourceLocation source,
                                  SoftwareFactoryWorkflow.Request request,
                                  SourceResolutionActivities.Result resolved,
                                  String role, String operation, String step,
                                  Map<String, String> inputDigests,
                                  com.example.aifactory.service.PipelineStepContracts.ArtifactReference validationError,
                                  int repairAttempt) {
        currentStep = step;
        var stepCommand = command(request, resolved, step, inputDigests);
        boolean sandboxPreparation = "ASSESS_TESTS".equals(operation);
        PipelineExecutionActivities host = pipeline(source, sandboxPreparation ? "sandbox" : "llm",
                sandboxPreparation ? TemporalActivityPolicies.Kind.SANDBOX : TemporalActivityPolicies.Kind.LLM);
        PipelineExecutionActivities.PipelineAgentInput prepared = host.prepareAgentInput(
                new PipelineExecutionActivities.PipelineAgentInputRequest(stepCommand, resolved.workspace(),
                        role, operation, validationError, repairAttempt));
        A2aContracts.AgentCardDescriptor card = a2a.resolveAgent().resolveAgent(role);
        String skill = role + ".pipeline-agent-task-v1";
        if (!card.skillIds().contains(skill)) {
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Agent Card does not expose the pipeline compatibility skill", "INCOMPATIBLE_SCHEMA");
        }
        String nodeId = "pipeline-" + step.replaceAll("[^A-Za-z0-9_-]", "-")
                + (repairAttempt > 0 ? "-" + repairAttempt : "");
        String delegationId = TemporalIds.delegation(request.taskId(), request.attemptId(), nodeId);
        var info = io.temporal.workflow.Workflow.getInfo();
        A2aExecutionContext execution = new A2aExecutionContext("1", request.taskId(), request.attemptId(),
                info.getWorkflowId(), info.getRunId(), request.repositoryId(), resolved.sourceCommit(),
                delegationId, null, role, List.of(String.valueOf(prepared.reference().data().get("digest"))));
        String messageId = TemporalIds.sha256(String.join("\n", request.taskId(), request.attemptId(),
                delegationId, role, String.valueOf(prepared.reference().data().get("digest")), card.cardDigest()));
        DelegationWorkflow.Budget budget = new DelegationWorkflow.Budget(12_000, 5_000_000, 6, 900);
        Map<String, Object> metadata = Map.of(
                A2aExtensions.EXECUTION_CONTEXT_V1, executionMetadata(execution),
                "budget", Map.of("maxTokens", budget.maxTokens(), "maxCostMicros", budget.maxCostMicros(),
                        "maxTurns", budget.maxTurns(), "timeoutSeconds", budget.timeoutSeconds()));
        A2aContracts.SendCommand send = new A2aContracts.SendCommand(role, skill, messageId, null, null,
                List.of(A2aEnvelopeFactory.create(role, skill, "pipeline-agent-result-v1",
                        List.of(prepared.reference()), budget)), metadata, true);
        A2aContracts.TaskSnapshot submitted = a2a.reconcileDispatch().reconcileDispatch(
                new A2aActivities.DispatchRequest(execution, card.cardDigest(), send));
        A2aContracts.Notification terminal = a2aTasks.awaitUntilTerminal(role, submitted,
                java.time.Duration.ofSeconds(30), a2a.getTask());
        if (terminal.state() != A2aContracts.TaskState.COMPLETED) {
            pipelineDelegations.add(new DelegationWorkflow.Result(delegationId, role,
                    terminal.state() == A2aContracts.TaskState.CANCELED ? "CANCELLED" : "FAILED"));
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Pipeline A2A agent ended in " + terminal.state(), "BUSINESS_REJECTION");
        }
        A2aContracts.TaskSnapshot completed = new A2aContracts.TaskSnapshot(terminal.taskId(),
                terminal.contextId(), terminal.state(), terminal.occurredAt(), terminal.artifacts(),
                Map.of("sequence", terminal.sequence()));
        A2aActivities.ValidatedArtifacts validated = a2a.validateArtifacts().validateArtifacts(
                new A2aActivities.ValidationRequest(role, "pipeline-agent-result-v1",
                        request.taskId(), request.attemptId(), completed));
        if (validated.references().size() != 1) {
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Pipeline A2A agent returned an ambiguous result set", "INCOMPATIBLE_SCHEMA");
        }
        pipelineDelegations.add(new DelegationWorkflow.Result(
                delegationId, role, "READY_FOR_ACTIVITIES", validated.references()));
        var consumed = host.consumeAgentResult(new PipelineExecutionActivities.PipelineAgentResultRequest(
                stepCommand, resolved.workspace(), role, operation, validated.references().getFirst(),
                prepared.supportingArtifact()));
        artifacts.putAll(consumed.artifacts());
        throwIfCancelled();
    }

    private static Map<String, Object> executionMetadata(A2aExecutionContext value) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("schemaVersion", value.schemaVersion());
        metadata.put("taskId", value.taskId());
        metadata.put("attemptId", value.attemptId());
        metadata.put("workflowId", value.workflowId());
        metadata.put("workflowRunId", value.workflowRunId());
        metadata.put("repositoryId", value.repositoryId());
        metadata.put("sourceCommit", value.sourceCommit());
        metadata.put("delegationId", value.delegationId());
        metadata.put("agentRole", value.agentRole());
        metadata.put("inputDigests", value.inputDigests());
        return Map.copyOf(metadata);
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

    public void approve(SoftwareFactoryWorkflow.ApprovalSignal signal) {
        approval = signal;
        delegate.approve(signal);
    }
    public void cancel(SoftwareFactoryWorkflow.CancellationSignal signal) {
        cancellation = signal;
        delegate.cancel(signal);
    }
    public void decide(SoftwareFactoryWorkflow.HumanDecisionSignal signal) {
        if (signal != null && signal.decisionId() != null) humanDecisions.put(signal.decisionId(), signal);
        delegate.decide(signal);
    }
    public void a2aTaskUpdate(com.example.aifactory.a2a.A2aContracts.Notification notification) {
        a2aTasks.accept(notification);
    }
    public String status() { return "CREATED".equals(phase) ? delegate.status() : phase; }
    public List<SoftwareFactoryWorkflow.DelegationView> dag() { return delegate.dag(); }
    public Map<String, DelegationWorkflow.Budget> budgets() { return delegate.budgets(); }
    public List<String> evidence() {
        return java.util.stream.Stream.concat(artifacts.values().stream().map(
                com.example.aifactory.service.PipelineStepContracts.ArtifactReference::uri),
                java.util.stream.Stream.concat(delegate.evidence().stream(), activeApprovalRequest == null
                        ? java.util.stream.Stream.empty()
                        : java.util.stream.Stream.of(activeApprovalRequest.uri())))
                .distinct().sorted().toList();
    }
    public List<SoftwareFactoryWorkflow.PendingEffectView> pendingEffects() {
        if ("WAITING_APPROVAL".equals(phase) && activeRequest != null && activeApprovalRequest != null
                && !approvalMatches(activeRequest)) {
            return List.of(new SoftwareFactoryWorkflow.PendingEffectView(
                    "APPROVAL", activeApprovalRequest.manifestId()));
        }
        return delegate.pendingEffects();
    }

    private static final class RequestedCancellation extends RuntimeException {}
}
