package com.example.aifactory.service;

import java.nio.file.Path;

public interface SandboxExecutor {
    String applyPatch(Path workspace, String taskId, String sourceCommit) throws Exception;

    String checkPatch(Path workspace, String taskId, String sourceCommit) throws Exception;

    String test(Path workspace, String taskId, String sourceCommit) throws Exception;

    String quality(Path workspace, String taskId, String sourceCommit) throws Exception;

    String security(Path workspace, String taskId, String sourceCommit) throws Exception;
}
