package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.PatchAttemptPolicy;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.List;

@WorkflowInterface
public interface PatchIntegrationWorkflow {
    String PATCH_CHECK_PROFILE = "patch-check-v1";
    String PATCH_APPLY_PROFILE = "patch-apply-v1";

    @WorkflowMethod(name = "PatchIntegrationWorkflow")
    Result run(Request request);

    record Request(String taskId, String attemptId, String sourceCommit, String workspace,
                   String planDigest, List<PatchIntegrationActivities.PatchArtifact> patches,
                   PatchAttemptPolicy.Decision authorization, PatchIntegrationActivities.CleanupPlan cleanupPlan) {
        public Request {
            patches = patches == null ? List.of() : List.copyOf(patches);
        }
    }

    record Result(String planDigest, String integratedPatchDigest, String validationProfile,
                  String applicationProfile, String diffCheckOutputDigest,
                  List<PatchIntegrationActivities.VerificationResult> verifications, String status) {
        public Result {
            verifications = verifications == null ? List.of() : List.copyOf(verifications);
        }
    }
}
