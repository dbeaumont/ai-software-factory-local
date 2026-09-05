package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.model.SandboxModels.Operation;

import java.nio.file.Path;
import java.util.Set;

public interface SandboxRuntime {
    RuntimeResult execute(Operation operation, String executionId, Path workspace) throws Exception;

    void cancel(String executionId);

    default void reconcileOrphans() throws Exception {
        // Runtimes without an external job backend have nothing to reconcile.
    }

    default void reconcileOrphans(Set<String> retainedExecutionIds) throws Exception {
        reconcileOrphans();
    }

    record RuntimeResult(int exitCode, String output, boolean outputTruncated) {
        public RuntimeResult(int exitCode, String output) {
            this(exitCode, output, false);
        }
    }

    final class RuntimeTimeoutException extends Exception {
        private final String partialOutput;
        private final boolean outputTruncated;

        public RuntimeTimeoutException(String message) {
            this(message, "", false);
        }

        public RuntimeTimeoutException(String message, String partialOutput, boolean outputTruncated) {
            super(message);
            this.partialOutput = partialOutput;
            this.outputTruncated = outputTruncated;
        }

        public String partialOutput() {
            return partialOutput;
        }

        public boolean outputTruncated() {
            return outputTruncated;
        }
    }
}
