package com.example.aifactory.service;

import java.nio.file.Path;

public interface RepositoryContextProvider {
    String collect(Path repository, String taskId, String sourceCommit) throws Exception;
}

