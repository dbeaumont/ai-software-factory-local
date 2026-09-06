package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.service.PipelineStepContracts;
import com.example.aifactory.workflow.EvidenceRepository;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Map;

@ActivityInterface
public interface PipelineExecutionActivities {
    @ActivityMethod(name = "BindResolvedSource")
    PipelineStepContracts.Result bindSource(SourceBinding binding);

    @ActivityMethod(name = "ExecutePipelineStep")
    PipelineStepContracts.Result execute(StepRequest request);

    @ActivityMethod(name = "ValidatePatchCandidate")
    PatchValidationResult validatePatchCandidate(StepRequest request);

    @ActivityMethod(name = "PreparePipelineA2aInput")
    PipelineAgentInput prepareAgentInput(PipelineAgentInputRequest request);

    @ActivityMethod(name = "ConsumePipelineA2aResult")
    PipelineStepContracts.Result consumeAgentResult(PipelineAgentResultRequest request);

    @ActivityMethod(name = "PreparePipelineDelivery")
    PendingEffect prepareDelivery(DeliveryRequest request);

    @ActivityMethod(name = "DeliverPipelinePullRequest")
    String deliver(DeliveryRequest request);

    @ActivityMethod(name = "RecordPipelineGateRejection")
    void recordGateRejection(GateRejection rejection);

    @ActivityMethod(name = "RecordPipelineCancellation")
    void recordCancellation(Cancellation cancellation);

    @ActivityMethod(name = "RecordPipelineApproval")
    void recordApproval(Approval approval);

    @ActivityMethod(name = "RecordPipelineHumanDecision")
    void recordHumanDecision(HumanDecision decision);

    @ActivityMethod(name = "CreatePipelineApprovalManifest")
    EvidenceRepository.StoredManifest createApprovalManifest(ApprovalManifestRequest request);

    record SourceBinding(String taskId, String attemptId, String repositoryId, String sourceCommit,
                         String workspace, String attestationDigest) {}

    record StepRequest(PipelineStepContracts.Command command, String workspace) {}

    record PatchValidationResult(boolean valid, PipelineStepContracts.Result result,
                                 PipelineStepContracts.ArtifactReference validationError) {}

    record PipelineAgentInputRequest(PipelineStepContracts.Command command, String workspace,
                                     String role, String operation,
                                     PipelineStepContracts.ArtifactReference validationError,
                                     int repairAttempt) {}

    record PipelineAgentInput(A2aContracts.Part reference,
                              PipelineStepContracts.ArtifactReference supportingArtifact) {}

    record PipelineAgentResultRequest(PipelineStepContracts.Command command, String workspace,
                                      String role, String operation,
                                      A2aActivities.EvidenceReference resultReference,
                                      PipelineStepContracts.ArtifactReference supportingArtifact) {}

    record DeliveryRequest(String taskId, String attemptId, String sourceCommit) {}

    record GateRejection(String taskId, String attemptId, String sourceCommit, String gate) {}

    record Cancellation(String taskId, String attemptId, String sourceCommit, String reasonDigest, String actor) {}

    record Approval(String taskId, String attemptId, String sourceCommit, String manifestId,
                    String manifestDigest, String actor, String decidedAt) {}

    record HumanDecision(String taskId, String attemptId, String sourceCommit, String requestId,
                         String decision, String objectDigest, String actor, String actorRole,
                         String decidedAt) {}

    record ApprovalManifestRequest(String taskId, String attemptId, String repositoryId, String sourceCommit,
                                   Map<String, PipelineStepContracts.ArtifactReference> artifacts) {
        public ApprovalManifestRequest {
            artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
        }
    }
}
