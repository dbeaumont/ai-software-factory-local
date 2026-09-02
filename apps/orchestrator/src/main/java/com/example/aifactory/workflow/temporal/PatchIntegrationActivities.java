package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;

/** Workflow-only effect boundary for materializing and applying immutable Code patch evidence. */
@ActivityInterface
public interface PatchIntegrationActivities {
    @ActivityMethod(name = "ApplyPatchIntegration")
    ApplicationResult apply(Request request);

    @ActivityMethod(name = "VerifyPatchIntegration")
    VerificationResult verify(VerificationRequest request);

    @ActivityMethod(name = "CleanupPatchIntegration")
    CleanupResult cleanup(CleanupRequest request);

    record PatchArtifact(String nodeId, String proposalId, String uri, String digest) {}

    record Request(DurableExecutionActivities.Metadata metadata, String workspace, String planDigest,
                   String validationProfile, String applicationProfile, List<PatchArtifact> patches) {
        public Request {
            patches = patches == null ? List.of() : List.copyOf(patches);
        }
    }

    record ApplicationResult(String planDigest, String integratedPatchDigest, String validationProfile,
                             String applicationProfile, String diffCheckOutputDigest, String status) {}

    record VerificationRequest(DurableExecutionActivities.Metadata metadata, String workspace,
                               String integratedPatchDigest, VerificationKind kind) {}

    record VerificationResult(VerificationKind kind, String outputDigest, String status) {}

    record WorktreeRef(String worktreeId, String taskId, String attemptId, String nodeId,
                       String sourceCommit, String path) {}

    record CleanupPlan(String sourceRepository, String isolationRoot, String integrationWorkspace,
                       List<WorktreeRef> worktrees) {
        public CleanupPlan {
            worktrees = worktrees == null ? List.of() : List.copyOf(worktrees);
        }
    }

    record CleanupRequest(DurableExecutionActivities.Metadata metadata, CleanupPlan plan,
                          TerminalOutcome outcome) {}

    record CleanupResult(TerminalOutcome outcome, int removedWorktrees,
                         boolean invalidPatchRemoved, boolean consolidatedPatchRemoved, String status) {}

    enum VerificationKind { TESTS, QUALITY, SECURITY }

    enum TerminalOutcome { SUCCESS, FAILED, TIMED_OUT, CANCELLED }
}
