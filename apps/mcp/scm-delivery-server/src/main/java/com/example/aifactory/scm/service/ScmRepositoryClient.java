package com.example.aifactory.scm.service;

public interface ScmRepositoryClient {
    RepositoryMetadata getRepository(RepositoryRegistry.RepositoryDefinition repository);

    Revision resolveRevision(RepositoryRegistry.RepositoryDefinition repository, String branch);

    record RepositoryMetadata(String repositoryId, String owner, String name, String defaultBranch,
                              boolean archived, boolean empty) {
    }

    record Revision(String repositoryId, String ref, String sourceCommit) {
    }
}
