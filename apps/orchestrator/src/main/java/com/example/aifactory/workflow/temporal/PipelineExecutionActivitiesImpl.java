package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.TemporalProperties;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.service.PipelineProjectionEvent;
import com.example.aifactory.service.PipelineStepContracts;
import com.example.aifactory.service.PipelineStepService;
import com.example.aifactory.workflow.TaskMemory;
import com.example.aifactory.workflow.EvidenceRepository;
import io.temporal.activity.Activity;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/** Host-side adapter from compact Temporal commands to extracted pipeline steps and projection events. */
@Component
public final class PipelineExecutionActivitiesImpl implements PipelineExecutionActivities {
    private static final Map<String, String> STEP_WORKERS = Map.of(
            "plan", "llm", "generate-patch", "llm", "review", "llm",
            "apply-patch", "sandbox", "test", "sandbox",
            "quality", "assurance", "security", "assurance");
    private final PipelineStepService steps;
    private final TaskMemory memory;
    private final Map<String, String> taskQueues;
    private final EvidenceRepository evidence;

    public PipelineExecutionActivitiesImpl(PipelineStepService steps, TaskMemory memory,
                                           TemporalProperties properties, EvidenceRepository evidence) {
        this.steps = steps;
        this.memory = memory;
        this.taskQueues = properties.taskQueues();
        this.evidence = evidence;
    }

    @Override
    public PipelineStepContracts.Result bindSource(SourceBinding binding) {
        requireQueue("context");
        if (binding == null || binding.sourceCommit() == null || !binding.sourceCommit().matches("[0-9a-f]{40}")
                || binding.workspace() == null || binding.workspace().isBlank()
                || binding.attestationDigest() == null || !binding.attestationDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Resolved source binding is invalid");
        }
        TaskState state = requireTask(binding.taskId(), binding.attemptId());
        state.transition(TaskStatus.CLONING, "Source resolved and attested by Temporal");
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.WorkspaceInitialized(binding.workspace()));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.SourceCloned(
                binding.sourceCommit(), "configured-cloud-model"));
        project(state);
        PipelineStepContracts.Command command = command("bind-source", binding.taskId(), binding.attemptId(),
                binding.repositoryId(), binding.sourceCommit(), Map.of("attestation", binding.attestationDigest()));
        return PipelineStepContracts.Result.from(command, binding.sourceCommit(), Map.of());
    }

    @Override
    public PipelineStepContracts.Result execute(StepRequest request) {
        if (request == null || request.command() == null || request.workspace() == null) {
            throw new IllegalArgumentException("Pipeline activity request is invalid");
        }
        PipelineStepContracts.Command command = request.command();
        String workerKind = STEP_WORKERS.get(command.step());
        if (workerKind == null) throw new IllegalArgumentException("Unsupported Temporal pipeline step");
        requireQueue(workerKind);
        TaskState state = requireTask(command.taskId(), command.attemptId());
        if (!command.sourceCommit().equals(state.sourceCommit) || !request.workspace().equals(state.workspace)) {
            throw new SecurityException("Pipeline step is not bound to the projected source");
        }
        try {
            state.transition(status(command.step()), "Temporal activity: " + command.step());
            project(state, "started");
            Path workspace = Path.of(request.workspace());
            PipelineProjectionEvent.StepExecution execution = switch (command.step()) {
                case "plan" -> steps.plan(state, workspace, command);
                case "generate-patch" -> steps.generateAndRepairPatch(state, workspace, command);
                case "apply-patch" -> steps.applyPatch(state, workspace, command);
                case "test" -> steps.test(state, workspace, command);
                case "quality" -> steps.quality(state, workspace, command);
                case "security" -> steps.security(state, workspace, command);
                case "review" -> steps.review(state, workspace, command);
                default -> throw new IllegalArgumentException("Unsupported Temporal pipeline step");
            };
            execution.events().forEach(event -> PipelineProjectionEvent.Applier.apply(state, event));
            applyArtifacts(state, execution.result());
            project(state, "completed");
            return execution.result();
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Pipeline step failed: " + command.step(), failure));
        }
    }

    @Override
    public PipelineStepContracts.Result generatePatchCandidate(StepRequest request) {
        requireStepRequest(request, "generate-patch-candidate", "llm");
        TaskState state = requireTask(request.command().taskId(), request.command().attemptId());
        try {
            state.transition(TaskStatus.GENERATING_PATCH, "Temporal activity: generate patch candidate");
            project(state, "started");
            PipelineProjectionEvent.StepExecution execution = steps.generatePatchCandidate(
                    state, Path.of(request.workspace()), request.command());
            applyAndProject(state, execution, "completed");
            return execution.result();
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Patch candidate generation failed", failure));
        }
    }

    @Override
    public PatchValidationResult validatePatchCandidate(StepRequest request) {
        requireStepRequest(request, "validate-patch-candidate", "sandbox");
        TaskState state = requireTask(request.command().taskId(), request.command().attemptId());
        state.transition(TaskStatus.APPLYING_PATCH, "Temporal activity: validate patch candidate");
        project(state, "started");
        PipelineStepService.PatchValidationOutcome outcome = steps.validatePatchCandidate(
                state, Path.of(request.workspace()), request.command());
        applyAndProject(state, outcome.execution(), "completed");
        return new PatchValidationResult(outcome.valid(), outcome.execution().result(), outcome.error());
    }

    @Override
    public PipelineStepContracts.Result repairPatchCandidate(PatchRepairRequest request) {
        if (request == null) throw new IllegalArgumentException("Patch repair request is invalid");
        StepRequest step = new StepRequest(request.command(), request.workspace());
        requireStepRequest(step, "repair-patch-candidate", "llm");
        TaskState state = requireTask(request.command().taskId(), request.command().attemptId());
        try {
            PipelineProjectionEvent.StepExecution execution = steps.repairPatchCandidate(state,
                    Path.of(request.workspace()), request.command(), request.validationError(),
                    request.repairAttempt());
            applyAndProject(state, execution);
            return execution.result();
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new IllegalStateException("Patch repair failed", failure));
        }
    }

    @Override
    public PendingEffect prepareDelivery(DeliveryRequest request) {
        requireQueue("scm");
        TaskState state = requireTask(request.taskId(), request.attemptId());
        if (!request.sourceCommit().equals(state.sourceCommit)) {
            throw new SecurityException("Delivery preparation is not source-bound");
        }
        PipelineProjectionEvent.DeliveryPrepared prepared = steps.prepareDelivery(state);
        PipelineProjectionEvent.Applier.apply(state, prepared);
        state.transition(TaskStatus.WAITING_APPROVAL, "Pipeline complete; Temporal awaits approval");
        project(state);
        return prepared.pendingEffect();
    }

    @Override
    public String deliver(DeliveryRequest request) {
        requireQueue("scm");
        TaskState state = requireTask(request.taskId(), request.attemptId());
        if (!request.sourceCommit().equals(state.sourceCommit) || !state.humanApproved) {
            throw new SecurityException("SCM delivery is not source-bound and approved");
        }
        try {
            PipelineStepContracts.Command command = command("delivery", request.taskId(), request.attemptId(),
                    com.example.aifactory.service.ScmDeliveryGateway.repositoryId(state.request.repositoryUrl()),
                    request.sourceCommit(), Map.of("patch", TemporalIds.sha256(state.patch)));
            PipelineProjectionEvent.StepExecution execution = steps.deliver(state, command);
            execution.events().forEach(event -> PipelineProjectionEvent.Applier.apply(state, event));
            applyArtifacts(state, execution.result());
            state.transition(TaskStatus.PR_CREATED, "Pull request created by Temporal SCM activity");
            project(state);
            return state.pullRequestUrl;
        } catch (RuntimeException failure) {
            throw TemporalFailureClassifier.toApplicationFailure(failure);
        } catch (Exception failure) {
            throw TemporalFailureClassifier.toApplicationFailure(
                    new TemporalFailureClassifier.EffectOutcomeUnknownException(
                            "SCM acknowledgement was not confirmed; reconciliation is required", failure));
        }
    }

    @Override
    public void recordGateRejection(GateRejection rejection) {
        requireQueue("evidence");
        if (rejection == null || rejection.gate() == null
                || !rejection.gate().matches("[a-z][a-z0-9-]{1,63}")) {
            throw new IllegalArgumentException("Pipeline gate rejection is invalid");
        }
        TaskState state = requireTask(rejection.taskId(), rejection.attemptId());
        if (!rejection.sourceCommit().equals(state.sourceCommit)) {
            throw new SecurityException("Pipeline gate rejection is not source-bound");
        }
        state.transition(TaskStatus.GATE_REJECTED, "Gate rejected: " + rejection.gate());
        project(state);
    }

    @Override
    public void recordCancellation(Cancellation cancellation) {
        requireQueue("evidence");
        if (cancellation == null || cancellation.reasonDigest() == null
                || !cancellation.reasonDigest().matches("[0-9a-f]{64}")
                || cancellation.actor() == null || cancellation.actor().isBlank()) {
            throw new IllegalArgumentException("Pipeline cancellation is invalid");
        }
        TaskState state = requireTask(cancellation.taskId(), cancellation.attemptId());
        if (!cancellation.sourceCommit().equals(state.sourceCommit)) {
            throw new SecurityException("Pipeline cancellation is not source-bound");
        }
        state.cancel(cancellation.reasonDigest(), cancellation.actor());
        project(state);
    }

    @Override
    public void recordApproval(Approval approval) {
        requireQueue("evidence");
        if (approval == null || approval.manifestId() == null
                || !approval.manifestId().matches("[0-9a-f]{64}")
                || approval.manifestDigest() == null || !approval.manifestDigest().matches("[0-9a-f]{64}")
                || approval.actor() == null || approval.actor().isBlank()) {
            throw new IllegalArgumentException("Pipeline approval is invalid");
        }
        try {
            java.time.Instant.parse(approval.decidedAt());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Pipeline approval timestamp is invalid", invalid);
        }
        TaskState state = requireTask(approval.taskId(), approval.attemptId());
        if (!approval.sourceCommit().equals(state.sourceCommit) || state.pendingEffect == null
                || !approval.manifestId().equals(state.pendingEffect.manifestId())
                || !approval.manifestDigest().equals(state.pendingEffect.manifestDigest())) {
            throw new SecurityException("Pipeline approval is not bound to the projected manifest");
        }
        if (!state.humanApproved) {
            state.humanApproved = true;
            state.transition(TaskStatus.APPROVED, "Temporal approval recorded for manifest " + approval.manifestId());
            project(state);
        }
    }

    @Override
    public void recordHumanDecision(HumanDecision decision) {
        requireQueue("evidence");
        if (decision == null) throw new IllegalArgumentException("Pipeline human decision is invalid");
        try {
            java.time.Instant.parse(decision.decidedAt());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Pipeline human decision timestamp is invalid", invalid);
        }
        TaskState state = requireTask(decision.taskId(), decision.attemptId());
        if (!decision.sourceCommit().equals(state.sourceCommit)) {
            throw new SecurityException("Pipeline human decision is not source-bound");
        }
        String eventId = projectionEventId();
        if (memory.wasProjected(state.id, state.workflowAttemptId, eventId)) return;
        state.answerHumanAction(decision.requestId(), decision.decision(), decision.objectDigest(),
                decision.actor(), decision.actorRole());
        memory.project(eventId, state);
    }

    @Override
    public EvidenceRepository.StoredManifest createApprovalManifest(ApprovalManifestRequest request) {
        requireQueue("evidence");
        TaskState state = requireTask(request.taskId(), request.attemptId());
        if (!request.sourceCommit().equals(state.sourceCommit)
                || request.artifacts().get("patch") == null) {
            throw new SecurityException("Approval manifest is not bound to the completed pipeline");
        }
        Map<String, EvidenceRepository.EvidenceReference> references = new java.util.LinkedHashMap<>();
        Map<String, String> digests = new java.util.LinkedHashMap<>();
        java.util.Set<String> required = java.util.Set.of(
                "plan", "patch", "tests", "quality", "security", "sbom", "review");
        required.stream().sorted().forEach(name -> {
            PipelineStepContracts.ArtifactReference artifact = request.artifacts().get(name);
            if (artifact == null) throw new SecurityException("Approval manifest is missing evidence: " + name);
            references.put(name, new EvidenceRepository.EvidenceReference(
                    artifact.uri(), artifact.digest(), artifact.status()));
            digests.put(name, artifact.digest());
        });
        EvidenceRepository.PolicyDecision policy = new EvidenceRepository.PolicyDecision(
                "1", request.taskId(), request.attemptId(), "pipeline-gates", "1", "ALLOW",
                java.util.List.of("all-pipeline-gates-passed"),
                Map.copyOf(digests), java.time.Instant.now());
        EvidenceRepository.StoredManifest manifest = evidence.createManifest(new EvidenceRepository.ManifestRequest(
                request.taskId(), request.attemptId(),
                request.repositoryId(), request.sourceCommit(), request.artifacts().get("patch").digest(),
                Map.copyOf(references), policy, "workflow"));
        state.bindApprovalManifest(manifest.manifestId(), manifest.uri(), manifest.digest());
        project(state);
        return manifest;
    }

    private TaskState requireTask(String taskId, String attemptId) {
        TaskState state = memory.find(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown task " + taskId));
        if (!state.workflowAttemptId.equals(attemptId)) {
            throw new IllegalArgumentException("Unknown pipeline attempt");
        }
        return state;
    }

    private void requireStepRequest(StepRequest request, String step, String workerKind) {
        if (request == null || request.command() == null || request.workspace() == null
                || !step.equals(request.command().step())) {
            throw new IllegalArgumentException("Pipeline activity request is invalid");
        }
        requireQueue(workerKind);
        TaskState state = requireTask(request.command().taskId(), request.command().attemptId());
        if (!request.command().sourceCommit().equals(state.sourceCommit)
                || !request.workspace().equals(state.workspace)) {
            throw new SecurityException("Pipeline step is not bound to the projected source");
        }
    }

    private void applyAndProject(TaskState state, PipelineProjectionEvent.StepExecution execution) {
        applyAndProject(state, execution, null);
    }

    private void applyAndProject(TaskState state, PipelineProjectionEvent.StepExecution execution,
                                 String checkpoint) {
        execution.events().forEach(event -> PipelineProjectionEvent.Applier.apply(state, event));
        applyArtifacts(state, execution.result());
        if (checkpoint == null) project(state); else project(state, checkpoint);
    }

    private void project(TaskState state) {
        memory.project(projectionEventId(), state);
    }

    private void project(TaskState state, String checkpoint) {
        memory.project(projectionEventId() + ':' + checkpoint, state);
    }

    private static String projectionEventId() {
        return Activity.getExecutionContext().getInfo().getActivityId();
    }

    private static void applyArtifacts(TaskState state, PipelineStepContracts.Result result) {
        result.artifacts().forEach((type, artifact) -> state.recordArtifact(type, type, artifact.status(),
                java.util.Set.of("security", "review").contains(type) ? "CONFIDENTIAL" : "INTERNAL",
                artifact.uri(), artifact.digest(), artifact.sizeBytes(), true));
    }

    private void requireQueue(String workerKind) {
        String actual = Activity.getExecutionContext().getInfo().getActivityTaskQueue();
        if (!taskQueues.get(workerKind).equals(actual)) {
            throw new SecurityException("Pipeline capability invoked on an unauthorized task queue");
        }
    }

    private static PipelineStepContracts.Command command(String step, String taskId, String attemptId,
                                                         String repositoryId, String sourceCommit,
                                                         Map<String, String> digests) {
        return new PipelineStepContracts.Command(PipelineStepContracts.SCHEMA_VERSION, step, taskId, attemptId,
                TemporalIds.workflow(taskId, attemptId), repositoryId, sourceCommit, digests);
    }

    private static TaskStatus status(String step) {
        return switch (step) {
            case "plan" -> TaskStatus.PLANNING;
            case "generate-patch" -> TaskStatus.GENERATING_PATCH;
            case "apply-patch" -> TaskStatus.APPLYING_PATCH;
            case "test" -> TaskStatus.TESTING;
            case "quality" -> TaskStatus.QUALITY_SCANNING;
            case "security" -> TaskStatus.SECURITY_SCANNING;
            case "review" -> TaskStatus.REVIEWING;
            default -> throw new IllegalArgumentException("Unsupported pipeline step");
        };
    }
}
