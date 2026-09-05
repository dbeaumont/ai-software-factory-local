package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.service.PipelineStepContracts;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PipelineExecutionActivities {
    @ActivityMethod(name = "BindResolvedSource")
    PipelineStepContracts.Result bindSource(SourceBinding binding);

    @ActivityMethod(name = "ExecutePipelineStep")
    PipelineStepContracts.Result execute(StepRequest request);

    @ActivityMethod(name = "GeneratePatchCandidate")
    PipelineStepContracts.Result generatePatchCandidate(StepRequest request);

    @ActivityMethod(name = "ValidatePatchCandidate")
    PatchValidationResult validatePatchCandidate(StepRequest request);

    @ActivityMethod(name = "RepairPatchCandidate")
    PipelineStepContracts.Result repairPatchCandidate(PatchRepairRequest request);

    @ActivityMethod(name = "PreparePipelineDelivery")
    PendingEffect prepareDelivery(DeliveryRequest request);

    record SourceBinding(String taskId, String attemptId, String repositoryId, String sourceCommit,
                         String workspace, String attestationDigest) {}

    record StepRequest(PipelineStepContracts.Command command, String workspace) {}

    record PatchRepairRequest(PipelineStepContracts.Command command, String workspace,
                              PipelineStepContracts.ArtifactReference validationError, int repairAttempt) {}

    record PatchValidationResult(boolean valid, PipelineStepContracts.Result result,
                                 PipelineStepContracts.ArtifactReference validationError) {}

    record DeliveryRequest(String taskId, String attemptId, String sourceCommit) {}
}
