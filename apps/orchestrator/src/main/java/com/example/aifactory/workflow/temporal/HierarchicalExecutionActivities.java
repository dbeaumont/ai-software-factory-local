package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.List;
import java.util.Set;

/** Materializes immutable, contract-valid inputs before a hierarchical A2A delegation starts. */
@ActivityInterface
public interface HierarchicalExecutionActivities {
    @ActivityMethod(name = "PrepareHierarchicalSpecialistTask")
    A2aContracts.Part prepareSpecialistTask(PrepareSpecialistTask request);

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
}
