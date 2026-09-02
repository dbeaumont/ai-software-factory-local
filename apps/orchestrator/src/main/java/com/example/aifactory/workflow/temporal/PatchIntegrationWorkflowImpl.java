package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.PatchIntegrationPlanner;
import com.example.aifactory.service.PatchAttemptPolicy;
import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Durable owner of patch application; callers cannot select sandbox profiles. */
public final class PatchIntegrationWorkflowImpl implements PatchIntegrationWorkflow {
    private final PatchIntegrationActivities activities = Workflow.newActivityStub(
            PatchIntegrationActivities.class, TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.SANDBOX));

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public Result run(Request request) {
        requireValid(request);
        PatchAttemptPolicy.requireAllowed(request.authorization(), PatchAttemptPolicy.Operation.INTEGRATION,
                request.planDigest(), request.authorization().attempt());
        int sequenceBase = (request.authorization().attempt() - 1) * 4;
        DurableExecutionActivities.Metadata metadata = DurableExecutionActivities.Metadata.deterministic(
                request.taskId(), request.attemptId(), request.sourceCommit(), "integration", "apply-patches",
                sequenceBase + 1);
        PatchIntegrationActivities.ApplicationResult applied = activities.apply(
                new PatchIntegrationActivities.Request(metadata, request.workspace(),
                request.planDigest(), PATCH_CHECK_PROFILE, PATCH_APPLY_PROFILE, request.patches()));
        List<PatchIntegrationActivities.VerificationResult> verifications = new ArrayList<>();
        PatchIntegrationActivities.VerificationKind[] kinds = PatchIntegrationActivities.VerificationKind.values();
        for (int index = 0; index < kinds.length; index++) {
            PatchIntegrationActivities.VerificationKind kind = kinds[index];
            DurableExecutionActivities.Metadata verificationMetadata = DurableExecutionActivities.Metadata.deterministic(
                    request.taskId(), request.attemptId(), request.sourceCommit(), "integration",
                    "verify-" + kind.name().toLowerCase(java.util.Locale.ROOT), sequenceBase + index + 2);
            verifications.add(activities.verify(new PatchIntegrationActivities.VerificationRequest(
                    verificationMetadata, request.workspace(), applied.integratedPatchDigest(), kind)));
        }
        return new Result(applied.planDigest(), applied.integratedPatchDigest(), applied.validationProfile(),
                applied.applicationProfile(), applied.diffCheckOutputDigest(), verifications, "VERIFIED");
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.workspace() == null || request.workspace().isBlank()
                || request.patches().isEmpty() || request.patches().size() > 4) {
            throw new IllegalArgumentException("Patch integration workflow request is invalid");
        }
        Set<String> nodes = new HashSet<>();
        Set<String> proposals = new HashSet<>();
        for (PatchIntegrationActivities.PatchArtifact patch : request.patches()) {
            if (patch == null || patch.nodeId() == null || !patch.nodeId().matches("[A-Za-z0-9_-]{1,128}")
                    || patch.proposalId() == null || !patch.proposalId().matches("[A-Za-z0-9_-]{1,128}")
                    || patch.uri() == null || !patch.uri().startsWith(
                    "evidence://" + request.taskId() + '/' + request.attemptId() + "/code-patch/")
                    || patch.digest() == null || !patch.digest().matches("[0-9a-f]{64}")
                    || !nodes.add(patch.nodeId()) || !proposals.add(patch.proposalId())) {
                throw new IllegalArgumentException("Patch integration workflow artifact is invalid");
            }
        }
        List<PatchIntegrationPlanner.PatchIdentity> identities = request.patches().stream().map(patch ->
                new PatchIntegrationPlanner.PatchIdentity(patch.nodeId(), patch.proposalId(), patch.digest())).toList();
        if (!PatchIntegrationPlanner.digestIdentities(identities).equals(request.planDigest())) {
            throw new IllegalArgumentException("Patch integration workflow plan digest is invalid");
        }
    }
}
