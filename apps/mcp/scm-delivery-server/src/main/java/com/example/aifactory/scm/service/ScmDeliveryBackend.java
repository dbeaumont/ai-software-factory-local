package com.example.aifactory.scm.service;

import java.nio.file.Path;

public interface ScmDeliveryBackend {
    DeliveryResult findExisting(DeliveryCommand command);

    DeliveryResult createDraftPullRequest(DeliveryCommand command) throws Exception;

    record DeliveryCommand(RepositoryRegistry.RepositoryDefinition repository, Path workspace, String taskId,
                           String sourceCommit, String baseBranch, String branch, String title) {
    }

    record DeliveryResult(String repositoryId, String branch, String commit, long pullRequestId,
                          String pullRequestUrl, boolean draft) {
    }
}
