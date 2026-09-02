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

    enum VerificationKind { TESTS, QUALITY, SECURITY }
}
