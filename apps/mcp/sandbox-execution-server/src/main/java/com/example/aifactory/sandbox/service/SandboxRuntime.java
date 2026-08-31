package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.model.SandboxModels.Operation;

import java.nio.file.Path;

public interface SandboxRuntime {
    RuntimeResult execute(Operation operation, String executionId, Path workspace) throws Exception;

    void cancel(String executionId);

    default void reconcileOrphans() throws Exception {
        // Runtimes without an external job backend have nothing to reconcile.
    }

    record RuntimeResult(int exitCode, String output) {
    }

    final class RuntimeTimeoutException extends Exception {
        public RuntimeTimeoutException(String message) {
            super(message);
        }
    }
}
