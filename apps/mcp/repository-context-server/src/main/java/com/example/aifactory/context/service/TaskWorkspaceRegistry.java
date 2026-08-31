package com.example.aifactory.context.service;

import com.example.aifactory.context.config.RepositoryContextProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

@Component
public class TaskWorkspaceRegistry {
    private final Path registryRoot;

    public TaskWorkspaceRegistry(RepositoryContextProperties properties) {
        this.registryRoot = properties.registryRoot().toAbsolutePath().normalize();
    }

    public synchronized void verifyOrRegister(String taskId, Path workspace, String sourceCommit) throws Exception {
        Files.createDirectories(registryRoot);
        Path registry = registryRoot.resolve(taskId + ".registry").normalize();
        if (!registry.getParent().equals(registryRoot)) {
            throw new IllegalArgumentException("invalid task registry path");
        }
        String normalizedWorkspace = workspace.toRealPath().toString();
        if (Files.exists(registry)) {
            List<String> lines = Files.readAllLines(registry, StandardCharsets.UTF_8);
            if (lines.size() != 2 || !lines.get(0).equals(normalizedWorkspace) || !lines.get(1).equals(sourceCommit)) {
                throw new SecurityException("task workspace registration conflicts with the pinned root or commit");
            }
            return;
        }
        Path temporary = Files.createTempFile(registryRoot, taskId + '.', ".tmp");
        try {
            Files.writeString(temporary, normalizedWorkspace + '\n' + sourceCommit + '\n', StandardCharsets.UTF_8);
            try {
                Files.move(temporary, registry, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, registry);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
