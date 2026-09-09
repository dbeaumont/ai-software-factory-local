package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.service.IndependentReviewBundle;
import com.example.aifactory.service.PipelineStepContracts;
import com.example.aifactory.workflow.EvidenceRepository;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Materializes immutable, contract-valid inputs before a hierarchical A2A delegation starts. */
@ActivityInterface
public interface HierarchicalExecutionActivities {
    @ActivityMethod(name = "PrepareHierarchicalSpecialistTask")
    A2aContracts.Part prepareSpecialistTask(PrepareSpecialistTask request);

    @ActivityMethod(name = "AcceptHierarchicalSpecialistResult")
    AcceptedSpecialistResult acceptSpecialistResult(AcceptSpecialistResult request);

    @ActivityMethod(name = "PrepareHierarchicalDeveloperTasks")
    List<DeveloperTask> prepareDeveloperTasks(PrepareDeveloperTasks request);

    @ActivityMethod(name = "PrepareHierarchicalShortDeveloperTasks")
    List<DeveloperTask> prepareShortDeveloperTasks(PrepareShortDeveloperTasks request);

    @ActivityMethod(name = "AcceptHierarchicalDeveloperPatches")
    AcceptedDeveloperPatches acceptDeveloperPatches(AcceptDeveloperPatches request);

    @ActivityMethod(name = "PrepareHierarchicalPatchRepair")
    PatchRepairTask preparePatchRepair(PreparePatchRepair request);

    @ActivityMethod(name = "AcceptHierarchicalPatchRepair")
    AcceptedPatchRepair acceptPatchRepair(AcceptPatchRepair request);

    @ActivityMethod(name = "PrepareHierarchicalIndependentReview")
    PreparedIndependentReview prepareIndependentReview(PrepareIndependentReview request);

    @ActivityMethod(name = "AcceptHierarchicalIndependentReview")
    PipelineStepContracts.ArtifactReference acceptIndependentReview(AcceptIndependentReview request);

    record PrepareSpecialistTask(String taskId, String attemptId, String repositoryId, String sourceCommit,
                                 String delegationPlanId, String nodeId, String parentRole, String role,
                                 List<InputEvidence> inputs, Set<String> readPaths, Set<String> writePaths,
                                 Set<String> allowedTools, DelegationWorkflow.Budget budget,
                                 List<String> successCriteria) {
        public PrepareSpecialistTask {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            readPaths = readPaths == null ? Set.of() : Set.copyOf(readPaths);
            writePaths = writePaths == null ? Set.of() : Set.copyOf(writePaths);
            allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
            successCriteria = successCriteria == null ? List.of() : List.copyOf(successCriteria);
        }
    }

    record InputEvidence(String kind, String uri, String digest) {}

    record AcceptSpecialistResult(String taskId, String attemptId, String sourceCommit, String role,
                                  String contract, A2aActivities.EvidenceReference reference,
                                  Set<String> allowedReferenceIds, boolean activateAsCodePlan) {
        public AcceptSpecialistResult {
            allowedReferenceIds = allowedReferenceIds == null ? Set.of() : Set.copyOf(allowedReferenceIds);
        }
    }

    record AcceptedSpecialistResult(String documentId,
                                    PipelineStepContracts.ArtifactReference artifact) {}

    record PrepareDeveloperTasks(String taskId, String attemptId, String repositoryId, String sourceCommit,
                                 String delegationPlanId, String architectureAssessmentId,
                                 A2aActivities.EvidenceReference architectureReference,
                                 A2aActivities.EvidenceReference integrationReference,
                                 DelegationWorkflow.Budget budget) {}

    record PrepareShortDeveloperTasks(String taskId, String attemptId, String repositoryId, String sourceCommit,
                                      String delegationPlanId, A2aActivities.EvidenceReference planReference,
                                      DelegationWorkflow.Budget budget) {}

    record DeveloperTask(String nodeId, String codeTaskId, Set<String> dependsOn,
                         DelegationWorkflow.Budget budget,
                         A2aContracts.Part inputReference) {
        public DeveloperTask {
            dependsOn = dependsOn == null ? Set.of() : Set.copyOf(dependsOn);
        }
    }

    record AcceptDeveloperPatches(String taskId, String attemptId, String sourceCommit,
                                  List<DeveloperPatchResult> results) {
        public AcceptDeveloperPatches {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    record DeveloperPatchResult(DeveloperTask task, A2aActivities.EvidenceReference resultReference) {}

    record AcceptedDeveloperPatches(PipelineStepContracts.ArtifactReference patchCandidate,
                                    List<ReviewedSpecialistResult> reviewedResults) {
        public AcceptedDeveloperPatches {
            reviewedResults = reviewedResults == null ? List.of() : List.copyOf(reviewedResults);
        }
    }

    record PreparePatchRepair(String taskId, String attemptId, String sourceCommit, String delegationPlanId,
                              int repairAttempt, PipelineStepContracts.ArtifactReference patchCandidate,
                              PipelineStepContracts.ArtifactReference validationError,
                              DelegationWorkflow.Budget budget) {}

    record PatchRepairTask(String nodeId, String repairTaskId, DelegationWorkflow.Budget budget,
                           A2aContracts.Part inputReference) {}

    record AcceptPatchRepair(String taskId, String attemptId, String sourceCommit, PatchRepairTask task,
                             A2aActivities.EvidenceReference resultReference) {}

    record AcceptedPatchRepair(PipelineStepContracts.ArtifactReference patchCandidate,
                               ReviewedSpecialistResult reviewedResult) {}

    record PrepareIndependentReview(String taskId, String attemptId, String repositoryId, String sourceCommit,
                                    Map<String, PipelineStepContracts.ArtifactReference> artifacts,
                                    List<ReviewedSpecialistResult> reviewedResults,
                                    Set<String> requiredRoles) {
        public PrepareIndependentReview {
            artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
            reviewedResults = reviewedResults == null ? List.of() : List.copyOf(reviewedResults);
            requiredRoles = requiredRoles == null ? Set.of() : Set.copyOf(requiredRoles);
        }
    }

    record ReviewedSpecialistResult(String documentId, String role,
                                    PipelineStepContracts.ArtifactReference artifact) {}

    record PreparedIndependentReview(IndependentReviewBundle bundle,
                                     EvidenceRepository.StoredManifest manifest) {}

    record AcceptIndependentReview(String taskId, String attemptId, String sourceCommit,
                                   IndependentReviewBundle bundle,
                                   A2aActivities.EvidenceReference reference) {}
}
