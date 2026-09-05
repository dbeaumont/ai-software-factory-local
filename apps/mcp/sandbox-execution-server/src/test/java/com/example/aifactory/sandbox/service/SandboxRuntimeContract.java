package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class SandboxRuntimeContract {
    @TempDir
    Path workspace;

    protected abstract SandboxRuntime successfulRuntime();

    protected abstract SandboxRuntime timedOutRuntime();

    @Test
    void preservesTheNormalizedRuntimeResult() throws Exception {
        SandboxRuntime.RuntimeResult result = successfulRuntime().execute(
                Operation.RUN_SECURITY, "a".repeat(32), workspace.resolve("task-1"));

        assertEquals(7, result.exitCode());
        assertEquals("bounded output", result.output());
        assertTrue(result.outputTruncated());
    }

    @Test
    void preservesTimeoutEvidence() {
        SandboxRuntime.RuntimeTimeoutException timeout = assertThrows(
                SandboxRuntime.RuntimeTimeoutException.class,
                () -> timedOutRuntime().execute(
                        Operation.RUN_SECURITY, "b".repeat(32), workspace.resolve("task-1")));

        assertEquals("partial output", timeout.partialOutput());
        assertTrue(timeout.outputTruncated());
    }

    @Test
    void rejectsAWorkspaceWithoutAnImmutableTestProfile() {
        assertThrows(IllegalArgumentException.class, () -> successfulRuntime().execute(
                Operation.RUN_TESTS, "c".repeat(32), workspace));
    }
}
