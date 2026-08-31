package com.example.aifactory.service;

import java.nio.file.Path;

public interface McpContextProvider {
    String collect(Path repository, String taskId, String sourceCommit);
}

