package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.PatchIntegrationPlanner;
import com.example.aifactory.service.PatchAttemptPolicy;
import io.temporal.common.VersioningBehavior;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TimeoutFailure;
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
        int sequenceBase = (request.authorization().attempt() - 1) * 5;
        PatchIntegrationActivities.TerminalOutcome outcome = PatchIntegrationActivities.TerminalOutcome.SUCCESS;
        try {
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
        } catch (RuntimeException failure) {
            outcome = terminalOutcome(failure);
            throw failure;
        } finally {
            PatchIntegrationActivities.TerminalOutcome cleanupOutcome = outcome;
            DurableExecutionActivities.Metadata cleanupMetadata = DurableExecutionActivities.Metadata.deterministic(
                    request.taskId(), request.attemptId(), request.sourceCommit(), "integration", "cleanup",
                    sequenceBase + 5);
            Workflow.newDetachedCancellationScope(() -> activities.cleanup(new PatchIntegrationActivities.CleanupRequest(
                    cleanupMetadata, request.cleanupPlan(), cleanupOutcome))).run();
        }
    }

    static PatchIntegrationActivities.TerminalOutcome terminalOutcome(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CanceledFailure) return PatchIntegrationActivities.TerminalOutcome.CANCELLED;
            if (current instanceof TimeoutFailure) return PatchIntegrationActivities.TerminalOutcome.TIMED_OUT;
            current = current.getCause();
        }
        return PatchIntegrationActivities.TerminalOutcome.FAILED;
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.workspace() == null || request.workspace().isBlank()
                || request.patches().isEmpty() || request.patches().size() > 4
                || !validCleanupPlan(request)) {
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

    private static boolean validCleanupPlan(Request request) {
        PatchIntegrationActivities.CleanupPlan cleanup = request.cleanupPlan();
        if (cleanup == null || !request.workspace().equals(cleanup.integrationWorkspace())
                || cleanup.sourceRepository() == null || cleanup.sourceRepository().isBlank()
                || cleanup.isolationRoot() == null || cleanup.isolationRoot().isBlank()
                || cleanup.worktrees().isEmpty()) return false;
        Set<String> patchNodes = request.patches().stream()
                .map(PatchIntegrationActivities.PatchArtifact::nodeId).collect(java.util.stream.Collectors.toSet());
        Set<String> cleanupNodes = new HashSet<>();
        for (PatchIntegrationActivities.WorktreeRef worktree : cleanup.worktrees()) {
            if (worktree == null || !request.taskId().equals(worktree.taskId())
                    || !request.attemptId().equals(worktree.attemptId())
                    || !request.sourceCommit().equals(worktree.sourceCommit())
                    || worktree.nodeId() == null || worktree.path() == null || worktree.path().isBlank()
                    || !cleanupNodes.add(worktree.nodeId())) return false;
        }
        return cleanupNodes.equals(patchNodes);
    }
}
