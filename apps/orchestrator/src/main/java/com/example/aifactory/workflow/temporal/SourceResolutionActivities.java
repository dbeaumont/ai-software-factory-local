package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface SourceResolutionActivities {
    @ActivityMethod(name = "ResolveAndAttestSource")
    Result resolve(Request request);

    record Request(String taskId, String attemptId, String repositoryId, String repositoryUrl,
                   String branch, String idempotencyKey) {}

    record Result(String repositoryId, String branch, String sourceCommit, String workspace,
                  String attestationDigest) {}
}
